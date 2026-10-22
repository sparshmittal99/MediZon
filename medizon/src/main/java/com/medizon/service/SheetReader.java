package com.medizon.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.ss.usermodel.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

    /** A run of words on one line, close enough together to be one table cell. */
    private record Piece(float x, float end, String text) { }

    /**
     * Rebuilds the table from where text sits on the page: words with a wide gap between them are separate cells, the
     * heading row's cells mark where each column starts, and every later cell joins the column it sits under.
     */
    static List<List<String>> pdf(byte[] bytes) throws IOException {
        List<List<Piece>> lines = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                private final List<TextPosition> line = new ArrayList<>();

                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (!line.isEmpty() && !positions.isEmpty()) line.add(null);     // a word break
                    line.addAll(positions);
                }

                @Override
                protected void writeLineSeparator() { flush(); }

                @Override
                protected void writePageEnd() { flush(); }

                private void flush() {
                    if (!line.isEmpty()) lines.add(cells(line));
                    line.clear();
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
        }
        return columns(lines);
    }

    /** Letters into words, words into cells: a gap wider than about two spaces starts a new cell. */
    private static List<Piece> cells(List<TextPosition> glyphs) {
        List<Piece> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        float start = -1, end = -1;
        boolean wordBreak = false;
        for (TextPosition g : glyphs) {
            if (g == null) { wordBreak = true; continue; }
            float space = Math.max(g.getWidthOfSpace(), g.getWidth() * 0.6f);
            boolean newCell = start >= 0 && g.getXDirAdj() - end > space * 2.2f;
            if (newCell) {
                out.add(new Piece(start, end, text.toString().trim()));
                text.setLength(0);
                start = -1;
            } else if (wordBreak && start >= 0) {
                text.append(' ');
            }
            if (start < 0) start = g.getXDirAdj();
            text.append(g.getUnicode());
            end = g.getXDirAdj() + g.getWidthDirAdj();
            wordBreak = false;
        }
        if (start >= 0) out.add(new Piece(start, end, text.toString().trim()));
        return out;
    }

    /**
     * Puts each line's cells under the columns of the widest line near the top (the headings): a column runs from its
     * heading's left edge to the next heading, and a cell belongs to the column its middle falls in, so left- and
     * right-aligned values both land right.
     */
    private static List<List<String>> columns(List<List<Piece>> lines) {
        List<Piece> header = lines.stream().limit(HEADER_SEARCH).max(Comparator.comparingInt(List::size)).orElse(List.of());
        if (header.size() < 2) return lines.stream().map(l -> l.stream().map(Piece::text).toList()).toList();
        float[] starts = new float[header.size()];
        for (int c = 0; c < header.size(); c++) starts[c] = header.get(c).x();
        List<List<String>> out = new ArrayList<>();
        for (List<Piece> line : lines) {
            String[] row = new String[header.size()];
            for (Piece p : line) {
                float mid = (p.x() + p.end()) / 2;
                int col = 0;
                for (int c = 0; c < starts.length; c++) if (mid >= starts[c] - 2) col = c;
                row[col] = row[col] == null ? p.text() : row[col] + " " + p.text();
            }
            out.add(Arrays.asList(row));
        }
        return out;
    }
}
