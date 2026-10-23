package com.medizon.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.ss.usermodel.*;
import org.springframework.web.multipart.MultipartFile;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Reads a table of products out of an uploaded file: an Excel workbook (.xlsx or .xls, its first sheet with
 * headings), a CSV file, or a PDF that has the table as text (one exported from Excel or a billing system, not a scan).
 * Headings are matched to {@link Column}s loosely ("Stock (packs)", "Qty" and "Quantity" all mean stock), so a
 * supplier's own sheet usually works as it is.
 */
public final class SheetReader {

    /** A column the importer understands: its key, its heading in templates, and the other headings that mean it. */
    public record Column(String key, String label, boolean required, String help, List<String> aliases) {
        public Column(String key, String label, boolean required, String help, String... aliases) {
            this(key, label, required, help, List.of(aliases));
        }

        /** The heading as it is, or without a unit in brackets: "Price (Rs)" and "MRP [₹]" mean price and MRP. */
        boolean matches(String heading) {
            if (heading == null) return false;
            return means(normalize(heading)) || means(normalize(heading.replaceAll("\\(.*?\\)|\\[.*?]", "")));
        }

        private boolean means(String h) {
            return !h.isEmpty() && (h.equals(normalize(label)) || aliases.stream().anyMatch(a -> normalize(a).equals(h)));
        }
    }

    /** One row of the table: its row number in the file (for messages) and its cells by column key. */
    public record Row(int number, Map<String, String> cells) implements Serializable {
        public String get(String key) {
            String v = cells.get(key);
            return v == null || v.isBlank() ? null : v.trim();
        }

        boolean isEmpty() { return cells.values().stream().allMatch(v -> v == null || v.isBlank()); }
    }

    /** Most rows read from one file. */
    public static final int MAX_ROWS = 2000;
    /** Rows searched for the headings, for sheets with a title or notes above the table. */
    private static final int HEADER_SEARCH = 15;

    private SheetReader() { }

    public static List<Row> read(MultipartFile file, List<Column> columns) {
        if (file == null || file.isEmpty()) throw new BusinessException("Choose an Excel, CSV or PDF file to import.");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        List<List<String>> table;
        try (InputStream in = file.getInputStream()) {
            if (name.endsWith(".pdf")) table = pdf(in.readAllBytes());
            else if (name.endsWith(".csv")) table = csv(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            else if (name.endsWith(".xlsx") || name.endsWith(".xls")) table = excel(in);
            else throw new BusinessException("Upload an Excel (.xlsx or .xls), CSV or PDF file.");
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException be) throw be;
            throw new BusinessException("That file couldn't be read. Check it opens, and that a PDF has text rather than a scanned picture.");
        }
        return rows(table, columns);
    }

    /** Finds the heading row, then maps each later row's cells to the columns found in it. */
    static List<Row> rows(List<List<String>> table, List<Column> columns) {
        int headerAt = -1;
        Map<Integer, String> keyAt = Map.of();
        for (int r = 0; r < Math.min(table.size(), HEADER_SEARCH) && headerAt < 0; r++) {
            Map<Integer, String> found = headings(table.get(r), columns);
            if (found.size() >= 2) {
                headerAt = r;
                keyAt = found;
            }
        }
        if (headerAt < 0) {
            throw new BusinessException("The column headings weren't found. Put them in the first row, e.g. "
                    + String.join(", ", columns.stream().limit(4).map(Column::label).toList()) + ". The template has them all.");
        }
        List<Row> out = new ArrayList<>();
        List<String> header = table.get(headerAt);
        for (int r = headerAt + 1; r < table.size(); r++) {
            List<String> cells = table.get(r);
            if (cells.equals(header)) continue;              // a PDF repeats the headings on every page
            Map<String, String> byKey = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> e : keyAt.entrySet()) {
                byKey.put(e.getValue(), e.getKey() < cells.size() ? cells.get(e.getKey()) : null);
            }
            Row row = new Row(r + 1, byKey);
            if (row.isEmpty()) continue;
            if (out.size() == MAX_ROWS) throw new BusinessException("Import at most " + MAX_ROWS + " rows at a time. Split the file and import each part.");
            out.add(row);
        }
        if (out.isEmpty()) throw new BusinessException("The file has headings but no products under them.");
        return out;
    }

    private static Map<Integer, String> headings(List<String> cells, List<Column> columns) {
        Map<Integer, String> found = new LinkedHashMap<>();
        for (int c = 0; c < cells.size(); c++) {
            String cell = cells.get(c);
            if (cell == null) continue;
            for (Column col : columns) {
                if (!found.containsValue(col.key()) && col.matches(cell)) {
                    found.put(c, col.key());
                    break;
                }
            }
        }
        return found;
    }

    /** "Stock (packs)" and "stock_packs" both become "stockpacks". */
    static String normalize(String heading) {
        return heading == null ? "" : heading.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ------------------------------------------------------------------ Excel

    private static List<List<String>> excel(InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            FormulaEvaluator formulas = wb.getCreationHelper().createFormulaEvaluator();
            DataFormatter text = new DataFormatter(Locale.ENGLISH);
            List<List<String>> out = new ArrayList<>();
            for (int r = 0; r <= sheet.getLastRowNum() && r <= MAX_ROWS + HEADER_SEARCH; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) cells.add(cell(row.getCell(c), formulas, text));
                }
                out.add(cells);
            }
            return out;
        }
    }

    /** Dates as yyyy-MM-dd and numbers in full (not as the sheet shows them, e.g. "1.2E+3" or "₹ 20"). */
    private static String cell(Cell cell, FormulaEvaluator formulas, DataFormatter text) {
        if (cell == null) return null;
        CellType type = cell.getCellType() == CellType.FORMULA ? formulas.evaluateFormulaCell(cell) : cell.getCellType();
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case STRING -> cell.getStringCellValue();
            case BLANK, ERROR, _NONE -> null;
            default -> text.formatCellValue(cell, formulas);
        };
    }

    // ------------------------------------------------------------------ CSV

    /** Comma separated, with "quoted, values" and "" for a quote inside them. */
    static List<List<String>> csv(String content) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String s = content.startsWith("﻿") ? content.substring(1) : content;    // Excel's byte order mark
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                out.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------ PDF

    /** One letter on the page: where it sits (y is its baseline, from the top of the page) and how big it is. */
    private record Glyph(float x, float end, float y, float size, float space, String text) { }

    /** A run of words on one line, close enough together to be one table cell. */
    /** {@code outside}: not part of the table, such as a title, notes or a page footer. */
    private record Piece(float x, float end, float y, float size, String text, boolean outside) {
        Piece(float x, float end, float y, float size, String text) { this(x, end, y, size, text, false); }

        float mid() { return (x + end) / 2; }

        Piece outsideTable() { return new Piece(x, end, y, size, text, true); }
    }

    /** A column's heading as it sits on the page, and its text (a heading can wrap onto two lines). */
    private record Heading(float x, float end, String text) { }

    private static boolean outside(List<Piece> row) { return row.stream().allMatch(Piece::outside); }

    /** Top to bottom, then left to right: the order a wrapped cell's lines are read in. */
    private static final Comparator<Piece> READING = Comparator.comparingDouble(Piece::y).thenComparingDouble(Piece::x);

    /**
     * Rebuilds the table from where text sits on the page. Letters on one baseline make a line, and a wide gap splits
     * a line into cells. Lines make one table row when they sit between the same two lines the table draws (or, with
     * none drawn, close together), so a cell that wraps onto several lines stays one cell. Every cell then joins the
     * column whose values it sits among, whichever way the headings and values are aligned.
     */
    static List<List<String>> pdf(byte[] bytes) throws IOException {
        List<List<Piece>> rows = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                rows.addAll(tableRows(lines(glyphs(doc, p)), Rules.of(doc.getPage(p - 1))));
            }
        }
        return columns(rows);
    }

    private static List<Glyph> glyphs(PDDocument doc, int page) throws IOException {
        List<Glyph> out = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition t) {
                if (t.getUnicode() == null || t.getUnicode().isBlank()) return;
                float size = t.getFontSizeInPt() > 0 ? t.getFontSizeInPt() : t.getHeightDir() * 1.4f;
                float space = t.getWidthOfSpace() > 0 && !Float.isNaN(t.getWidthOfSpace()) ? t.getWidthOfSpace() : size * 0.28f;
                out.add(new Glyph(t.getXDirAdj(), t.getXDirAdj() + t.getWidthDirAdj(), t.getYDirAdj(), size, space, letters(t.getUnicode())));
            }
        };
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.getText(doc);
        return out;
    }

    /** A ligature some fonts draw as one letter ("ﬁ" in "Cefixime") as the letters it is. */
    private static String letters(String glyph) {
        return glyph.chars().anyMatch(c -> c >= 0xFB00 && c <= 0xFB06) ? Normalizer.normalize(glyph, Normalizer.Form.NFKC) : glyph;
    }

    /** Letters on (nearly) the same baseline, top to bottom, each line split into cells. */
    private static List<List<Piece>> lines(List<Glyph> glyphs) {
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator.comparingDouble(Glyph::y));
        List<List<Piece>> out = new ArrayList<>();
        List<Glyph> line = new ArrayList<>();
        for (Glyph g : sorted) {
            if (!line.isEmpty() && g.y() - line.get(0).y() > Math.max(g.size(), line.get(0).size()) * 0.3f) {
                out.add(pieces(line));
                line = new ArrayList<>();
            }
            line.add(g);
        }
        if (!line.isEmpty()) out.add(pieces(line));
        return out;
    }

    /** Letters into words, words into cells: a small gap is a space, one wider than about two spaces starts a new cell. */
    private static List<Piece> pieces(List<Glyph> line) {
        line.sort(Comparator.comparingDouble(Glyph::x));
        float y = line.get(0).y(), size = (float) line.stream().mapToDouble(Glyph::size).max().orElse(0);
        List<Piece> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        float start = -1, end = -1;
        for (Glyph g : line) {
            float gap = g.x() - end;
            if (start >= 0 && gap > Math.max(g.space(), g.size() * 0.2f) * 2.2f) {
                out.add(new Piece(start, end, y, size, text.toString().trim()));
                text.setLength(0);
                start = -1;
            } else if (start >= 0 && gap > g.space() * 0.4f) {
                text.append(' ');
            }
            if (start < 0) start = g.x();
            text.append(g.text());
            end = Math.max(end, g.end());
        }
        if (start >= 0) out.add(new Piece(start, end, y, size, text.toString().trim()));
        return out;
    }

    /**
     * Lines grouped into table rows. When the table draws lines between its rows, lines between the same two of them
     * are one row. Within that (or the whole page, when it draws none), a line further below the last than lines of
     * text usually are starts a new row; without drawn lines, evenly spaced lines are one row each.
     */
    private static List<List<Piece>> tableRows(List<List<Piece>> lines, List<Float> rules) {
        Map<Integer, List<List<Piece>>> between = new LinkedHashMap<>();
        int outside = -1;
        for (List<Piece> line : lines) {
            if (line.isEmpty()) continue;
            float middle = line.get(0).y() - line.get(0).size() * 0.3f;
            int above = (int) rules.stream().filter(r -> r < middle).count();
            // Lines above or below every drawn line (a title, notes) are kept apart from the table's rows
            int key = above == 0 || above == rules.size() ? outside-- : above;
            between.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
        }
        boolean ruled = between.keySet().stream().filter(k -> k > 0).count() >= 3;
        List<List<Piece>> out = new ArrayList<>();
        if (ruled) {
            between.forEach((key, group) -> bySpacing(group, true).forEach(row ->
                    out.add(key > 0 ? row : row.stream().map(Piece::outsideTable).toList())));
        } else {
            // Without drawn lines, a row of one lone cell is a title, a note or a page footer, never a product
            bySpacing(lines, false).forEach(row -> out.add(row.size() > 1 ? row : row.stream().map(Piece::outsideTable).toList()));
        }
        return out;
    }

    private static List<List<Piece>> bySpacing(List<List<Piece>> lines, boolean ruled) {
        List<List<Piece>> kept = lines.stream().filter(l -> !l.isEmpty()).toList();
        float minGap = Float.MAX_VALUE, maxGap = 0;
        for (int k = 1; k < kept.size(); k++) {
            float gap = kept.get(k).get(0).y() - kept.get(k - 1).get(0).y();
            minGap = Math.min(minGap, gap);
            maxGap = Math.max(maxGap, gap);
        }
        // A wrapped cell leaves a smaller gap than the one between rows; evenly spaced lines have no wrapped cells
        boolean join = ruled || maxGap >= minGap * 1.3f;
        List<List<Piece>> rows = new ArrayList<>();
        List<Piece> row = null;
        Piece last = null;
        for (List<Piece> line : kept) {
            Piece first = line.get(0);
            if (row == null || !join || first.y() - last.y() > Math.max(first.size(), last.size()) * 1.5f) {
                row = new ArrayList<>();
                rows.add(row);
            }
            row.addAll(line);
            last = first;
        }
        return rows;
    }

    /**
     * Puts each row's cells under the headings: the row near the top with the most separate headings. A column's
     * values sit together, apart from the next column's, so a cell joins the column whose values it sits among (that
     * holds when headings are centred and values left-aligned); where that doesn't decide, the nearest heading.
     */
    private static List<List<String>> columns(List<List<Piece>> rows) {
        int headerAt = 0;
        List<Heading> header = List.of();
        for (int r = 0; r < Math.min(rows.size(), HEADER_SEARCH); r++) {
            List<Heading> h = headings(rows.get(r));
            if (h.size() > header.size()) {
                header = h;
                headerAt = r;
            }
        }
        if (header.size() < 2) return rows.stream().map(r -> r.stream().sorted(READING).map(Piece::text).toList()).toList();
        List<String> headerTexts = header.stream().map(Heading::text).toList();
        // Where values sit: the stretches covered by the cells of rows under the headings (not by a lone line of notes)
        List<float[]> spans = new ArrayList<>();
        rows.stream().skip(headerAt + 1L).filter(r -> r.size() >= 2 && !outside(r)).flatMap(List::stream)
                .sorted(Comparator.comparingDouble(Piece::x)).forEach(p -> {
                    float[] last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
                    if (last != null && p.x() <= last[1]) last[1] = Math.max(last[1], p.end());
                    else spans.add(new float[]{p.x(), p.end()});
                });
        List<List<String>> out = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<Piece> row = rows.get(r);
            if (r > headerAt && outside(row)) continue;   // page titles and footers between the table's rows
            if (headings(row).stream().map(Heading::text).toList().equals(headerTexts)) {
                out.add(headerTexts);                   // the headings, here or repeated on a later page
                continue;
            }
            String[] cells = new String[header.size()];
            for (Piece p : row.stream().sorted(READING).toList()) {
                int c = column(p, header, spans);
                cells[c] = cells[c] == null ? p.text() : cells[c] + " " + p.text();
            }
            out.add(Arrays.asList(cells));
        }
        return out;
    }

    /** A row's cells as headings: cells on different lines that overlap side to side are one wrapped heading. */
    private static List<Heading> headings(List<Piece> row) {
        List<List<Piece>> groups = new ArrayList<>();
        List<float[]> extents = new ArrayList<>();
        for (Piece p : row.stream().sorted(Comparator.comparingDouble(Piece::x)).toList()) {
            int last = groups.size() - 1;
            if (last >= 0 && p.x() < extents.get(last)[1]) {
                groups.get(last).add(p);
                extents.get(last)[1] = Math.max(extents.get(last)[1], p.end());
            } else {
                groups.add(new ArrayList<>(List.of(p)));
                extents.add(new float[]{p.x(), p.end()});
            }
        }
        List<Heading> out = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            String text = String.join(" ", groups.get(g).stream().sorted(READING).map(Piece::text).toList());
            out.add(new Heading(extents.get(g)[0], extents.get(g)[1], text));
        }
        return out;
    }

    private static int column(Piece p, List<Heading> header, List<float[]> spans) {
        float[] span = spans.stream().filter(s -> p.mid() >= s[0] && p.mid() <= s[1]).findFirst().orElse(null);
        List<Integer> among = new ArrayList<>();
        for (int c = 0; c < header.size(); c++) {
            if (span == null || (header.get(c).x() < span[1] && header.get(c).end() > span[0])) among.add(c);
        }
        if (among.isEmpty()) for (int c = 0; c < header.size(); c++) among.add(c);
        return nearest(p.mid(), header, among);
    }

    /** Of these headings, the one {@code x} falls under, or else the closest. */
    private static int nearest(float x, List<Heading> header, List<Integer> among) {
        int best = among.get(0);
        float bestDistance = Float.MAX_VALUE;
        for (int c : among) {
            Heading h = header.get(c);
            float d = x < h.x() ? h.x() - x : x > h.end() ? x - h.end() : 0;
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        return best;
    }

    /**
     * The lines a page draws across much of its width (and the edges of boxes that wide, such as shaded rows), from
     * the top of the page: where a table's rows begin and end.
     */
    private static final class Rules extends PDFGraphicsStreamEngine {
        private final float width, top;
        private final List<float[]> path = new ArrayList<>();
        /** Length drawn along each height, in half points. */
        private final Map<Integer, Float> lengthAt = new TreeMap<>();
        private Point2D current, start;

        private Rules(PDPage page) {
            super(page);
            PDRectangle box = page.getCropBox();
            width = box.getWidth();
            top = box.getUpperRightY();
        }

        static List<Float> of(PDPage page) {
            Rules r = new Rules(page);
            try {
                r.processPage(page);
            } catch (IOException | RuntimeException e) {
                return List.of();                       // rows are then found from how the lines are spaced
            }
            List<Float> out = new ArrayList<>();
            float height = page.getCropBox().getHeight();
            r.lengthAt.forEach((y, length) -> {
                float fromTop = r.top - y / 2f;
                // A page-sized background (as a browser prints one) has edges at the page's own top and bottom: not rows
                boolean pageEdge = fromTop < height * 0.01f || fromTop > height * 0.99f;
                if (length >= r.width * 0.3f && !pageEdge) out.add(fromTop);
            });
            Collections.sort(out);
            return out;
        }

        private void segment(Point2D a, Point2D b) {
            if (a != null && b != null) path.add(new float[]{(float) a.getX(), (float) a.getY(), (float) b.getX(), (float) b.getY()});
        }

        /** The path is drawn: its level stretches count. A path that only clips is never drawn. */
        private void commit() {
            for (float[] s : path) {
                if (Math.abs(s[1] - s[3]) < 0.5f && s[0] != s[2]) lengthAt.merge(Math.round(s[1] * 2), Math.abs(s[2] - s[0]), Float::sum);
            }
            path.clear();
        }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            segment(p0, p1);
            segment(p1, p2);
            segment(p2, p3);
            segment(p3, p0);
        }
        @Override public void moveTo(float x, float y) { current = start = new Point2D.Float(x, y); }
        @Override public void lineTo(float x, float y) {
            Point2D next = new Point2D.Float(x, y);
            segment(current, next);
            current = next;
        }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { current = new Point2D.Float(x3, y3); }
        @Override public Point2D getCurrentPoint() { return current; }
        @Override public void closePath() {
            segment(current, start);
            current = start;
        }
        @Override public void endPath() { path.clear(); }
        @Override public void strokePath() { commit(); }
        @Override public void fillPath(int windingRule) { commit(); }
        @Override public void fillAndStrokePath(int windingRule) { commit(); }
        @Override public void clip(int windingRule) { }
        @Override public void drawImage(PDImage pdImage) { }
        @Override public void shadingFill(COSName shadingName) { }
    }
}
