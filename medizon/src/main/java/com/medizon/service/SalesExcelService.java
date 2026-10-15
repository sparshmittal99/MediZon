package com.medizon.service;

import com.medizon.domain.Order;
import com.medizon.domain.OrderStatus;
import com.medizon.domain.ReturnRequest;
import com.medizon.domain.ReturnStatus;
import com.medizon.domain.Store;
import com.medizon.dto.Invoice;
import com.medizon.dto.ReportPeriod;
import com.medizon.dto.SalesDay;
import com.medizon.repository.OrderRepository;
import com.medizon.repository.ReturnRequestRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The sales report as an Excel workbook: a summary with GST totals, plus the sheets the admin picks.
 * Totals rows are formulas, so they stay right if rows are edited or filtered in Excel.
 * Tax is worked out the same way as on the invoices, for orders placed in the period that weren't cancelled.
 */
@Service
@RequiredArgsConstructor
public class SalesExcelService {

    public enum Section {
        MONTHLY("Month-wise", "Orders, sales, returns and net revenue for each month"),
        DAILY("Day-wise", "The same, for each day"),
        ORDERS("Orders", "Every order with its store, invoice number and GST split"),
        STORES("Store-wise", "Orders, sales and GST for each store"),
        MEDICINES("Medicines sold", "Packs and sales for each medicine"),
        HSN("HSN summary", "Taxable value and GST by HSN code and rate, for GST returns"),
        RETURNS("Returns", "Every return request and refund");

        private final String title;
        private final String description;

        Section(String title, String description) { this.title = title; this.description = description; }

        public String getTitle() { return title; }
        public String getDescription() { return description; }
    }

    private static final String[] SALES_COLUMNS = {"Orders placed", "Cancelled", "Packs sold", "Sales", "Round off", "Returns requested",
            "Refunds paid", "Refunded", "Net revenue"};

    private final SalesReportService report;
    private final OrderRepository orders;
    private final ReturnRequestRepository returns;
    private final InvoiceService invoices;

    @Transactional(readOnly = true)
    public void write(ReportPeriod period, Set<Section> sheets, OutputStream out) throws IOException {
        write(period, null, sheets, out);
    }

    /** The workbook for one store only, or for every store when {@code store} is null. */
    @Transactional(readOnly = true)
    public void write(ReportPeriod period, Store store, Set<Section> sheets, OutputStream out) throws IOException {
        LocalDateTime start = period.getFrom().atStartOfDay(), end = period.getTo().plusDays(1).atStartOfDay();
        Long storeId = store == null ? null : store.getId();
        List<Order> placed = orders.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(start, end);
        if (store != null) placed = placed.stream().filter(o -> inStore(o, storeId)).toList();
        Map<Long, Invoice> tax = new HashMap<>();
        placed.stream().filter(o -> o.getStatus() != OrderStatus.CANCELLED).forEach(o -> tax.put(o.getId(), invoices.breakdown(o)));
        List<SalesDay> days = report.daily(period.getFrom(), period.getTo(), storeId);

        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            Styles st = new Styles(wb);
            summary(wb, st, period, store, SalesDay.total(days), tax.values());
            if (sheets.contains(Section.MONTHLY)) salesTable(wb, st, Section.MONTHLY.title, report.monthly(period.getFrom(), period.getTo(), storeId));
            if (sheets.contains(Section.DAILY)) salesTable(wb, st, Section.DAILY.title, days);
            if (sheets.contains(Section.ORDERS)) ordersSheet(wb, st, placed, tax);
            if (sheets.contains(Section.STORES)) storesSheet(wb, st, placed, tax);
            if (sheets.contains(Section.MEDICINES)) medicinesSheet(wb, st, tax.values());
            if (sheets.contains(Section.HSN)) hsnSheet(wb, st, tax.values());
            if (sheets.contains(Section.RETURNS)) {
                List<ReturnRequest> list = returns.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(start, end);
                returnsSheet(wb, st, store == null ? list : list.stream().filter(x -> inStore(x.getOrder(), storeId)).toList());
            }
            wb.setForceFormulaRecalculation(true);
            wb.write(out);
        }
    }

    private static boolean inStore(Order o, Long storeId) { return o.getStore() != null && o.getStore().getId().equals(storeId); }

    // ------------------------------------------------------------------ sheets

    private void summary(SXSSFWorkbook wb, Styles st, ReportPeriod p, Store store, SalesDay t, Collection<Invoice> tax) {
        Sheet s = wb.createSheet("Summary");
        s.setColumnWidth(0, 34 * 256);
        s.setColumnWidth(1, 22 * 256);
        Invoice.Seller seller = invoices.seller();
        int r = 0;
        text(s.createRow(r++), 0, "MediZon sales report", st.title);
        text(s.createRow(r++), 0, seller.getName() + (seller.getGstin().isBlank() ? "" : "  ·  GSTIN " + seller.getGstin()), st.muted);
        r++;
        r = pair(s, r, "Store", store == null ? "All stores" : store.getName(), st);
        r = pair(s, r, "Period", p.getLabel(), st);
        r = pair(s, r, "From", p.getFrom(), st);
        r = pair(s, r, "To", p.getTo(), st);
        Row gen = s.createRow(r++);
        text(gen, 0, "Generated", st.label);
        Cell g = gen.createCell(1);
        g.setCellValue(LocalDateTime.now());
        g.setCellStyle(st.dateTime);
        r++;

        text(s.createRow(r++), 0, "Sales", st.section);
        r = pair(s, r, "Orders placed", t.getOrders(), st);
        r = pair(s, r, "Cancelled", t.getCancelled(), st);
        r = pair(s, r, "Orders completed", t.getCompletedOrders(), st);
        r = pair(s, r, "Packs sold", t.getPacks(), st);
        r = pair(s, r, "Sales (incl. GST and delivery)", t.getSales(), st);
        r = pair(s, r, "  of which round off", t.getRoundOff(), st);
        r = pair(s, r, "Average order value", t.getAverageOrder(), st);
        r = pair(s, r, "Returns requested", t.getReturns(), st);
        r = pair(s, r, "Refunds paid", t.getRefundCount(), st);
        r = pair(s, r, "Refunded amount", t.getRefunds(), st);
        r = pair(s, r, "Net revenue (sales - refunds)", t.getNet(), st);
        r++;

        text(s.createRow(r++), 0, "GST on orders placed in the period", st.section);
        r = pair(s, r, "Taxable value", sum(tax, Invoice::getTaxableTotal), st);
        r = pair(s, r, "CGST", sum(tax, Invoice::getCgstTotal), st);
        r = pair(s, r, "SGST", sum(tax, Invoice::getSgstTotal), st);
        r = pair(s, r, "IGST", sum(tax, Invoice::getIgstTotal), st);
        r = pair(s, r, "Total GST", sum(tax, Invoice::getTaxTotal), st);
        r = pair(s, r, "Invoice value before rounding", sum(tax, Invoice::getInvoiceValue), st);
        r = pair(s, r, "Round off", sum(tax, Invoice::getRoundOff), st);
        r = pair(s, r, "Invoice value (amount billed)", sum(tax, Invoice::getGrandTotal), st);
        r++;

        text(s.createRow(r++), 0, "Notes", st.section);
        for (String note : List.of(
                "Sales are what customers paid on orders that weren't cancelled, counted on the day the order was placed.",
                "Returns count on the day they were requested; refunds on the day they were paid out.",
                "GST is worked out from GST-inclusive prices, as on the invoices. Refunds aren't netted off the GST here.",
                "Bills are rounded to the nearest rupee. Round off is booked to its own ledger (indirect income when positive, "
                        + "indirect expense when negative) and is outside the taxable value and GST.",
                "Invoice numbers are issued when an order ships, so orders not yet shipped have none.")) {
            text(s.createRow(r++), 0, note, st.muted);
        }
    }

    /** Month-wise or day-wise sales table with a SUM totals row. */
    private void salesTable(SXSSFWorkbook wb, Styles st, String name, List<SalesDay> rows) {
        SXSSFSheet s = wb.createSheet(name);
        boolean monthly = !rows.isEmpty() && rows.get(0).isMonthly();
        String[] heads = new String[SALES_COLUMNS.length + 1];
        heads[0] = monthly ? "Month" : "Date";
        System.arraycopy(SALES_COLUMNS, 0, heads, 1, SALES_COLUMNS.length);
        header(s, st, heads, new int[]{14, 14, 11, 12, 16, 12, 17, 13, 16, 16});

        int r = 1;
        for (SalesDay d : rows) {
            Row row = s.createRow(r);
            Cell c = row.createCell(0);
            c.setCellValue(d.getDay());
            c.setCellStyle(monthly ? st.month : st.date);
            num(row, 1, d.getOrders(), st.integer);
            num(row, 2, d.getCancelled(), st.integer);
            num(row, 3, d.getPacks(), st.integer);
            money(row, 4, d.getSales(), st.money);
            money(row, 5, d.getRoundOff(), st.money);
            num(row, 6, d.getReturns(), st.integer);
            num(row, 7, d.getRefundCount(), st.integer);
            money(row, 8, d.getRefunds(), st.money);
            formula(row, 9, "E" + (r + 1) + "-I" + (r + 1), st.money);
            r++;
        }
        totals(s, st, r, 1, 9, Set.of(4, 5, 8, 9));
        s.createFreezePane(1, 1);
    }

    private void ordersSheet(SXSSFWorkbook wb, Styles st, List<Order> placed, Map<Long, Invoice> tax) {
        SXSSFSheet s = wb.createSheet(Section.ORDERS.title);
        header(s, st, new String[]{"Order date", "Order no.", "Invoice no.", "Invoice date", "Customer", "Place of supply",
                        "Status", "Payment", "Payment status", "Packs", "Items value", "Delivery", "Round off", "Order total",
                        "Taxable value", "CGST", "SGST", "IGST", "Total GST", "Store", "Seller GSTIN"},
                new int[]{14, 17, 19, 14, 22, 18, 12, 18, 14, 8, 13, 11, 11, 13, 14, 11, 11, 11, 12, 22, 18});
        int r = 1;
        for (Order o : placed) {
            Row row = s.createRow(r++);
            Invoice inv = tax.get(o.getId());
            date(row, 0, o.getCreatedAt().toLocalDate(), st);
            text(row, 1, o.getOrderNumber(), null);
            text(row, 2, o.getInvoiceNumber(), null);
            if (o.getInvoicedAt() != null) date(row, 3, o.getInvoicedAt().toLocalDate(), st);
            text(row, 4, o.getUser().getFullName(), null);
            text(row, 5, inv != null ? inv.getPlaceOfSupply() : o.getShippingState(), null);
            text(row, 6, o.getStatus().getLabel(), null);
            text(row, 7, o.getPaymentMethod().getLabel(), null);
            text(row, 8, capitalize(o.getPaymentStatus().name()), null);
            num(row, 9, o.getItemCount(), st.integer);
            money(row, 10, o.getSubtotal(), st.money);
            money(row, 11, o.getDeliveryFee(), st.money);
            money(row, 12, o.getRoundOff(), st.money);
            money(row, 13, o.getTotal(), st.money);
            if (inv != null) {
                money(row, 14, inv.getTaxableTotal(), st.money);
                money(row, 15, inv.getCgstTotal(), st.money);
                money(row, 16, inv.getSgstTotal(), st.money);
                money(row, 17, inv.getIgstTotal(), st.money);
                money(row, 18, inv.getTaxTotal(), st.money);
                text(row, 20, inv.getSeller().getGstin(), null);
            }
            text(row, 19, o.getStore() != null ? o.getStore().getName() : "", null);
        }
        // Totals leave cancelled orders out, like the rest of the report
        Row total = s.createRow(r);
        text(total, 0, "Total (excl. cancelled)", st.totalLabel);
        String status = "$G$2:$G$" + Math.max(2, r);
        for (int c = 9; c <= 18; c++) {
            String col = CellReference.convertNumToColString(c);
            formula(total, c, r == 1 ? "0" : "SUMIF(" + status + ",\"<>" + OrderStatus.CANCELLED.getLabel() + "\"," + col + "2:" + col + r + ")",
                    c == 9 ? st.totalInteger : st.totalMoney);
        }
        s.setAutoFilter(new CellRangeAddress(0, Math.max(0, r - 1), 0, 20));
        s.createFreezePane(2, 1);
    }

    /** Each store's orders, sales and GST in the period; cancelled orders count as orders but not as sales. */
    private void storesSheet(SXSSFWorkbook wb, Styles st, List<Order> placed, Map<Long, Invoice> tax) {
        Map<String, BigDecimal[]> byStore = new TreeMap<>();   // orders, cancelled, sales, round off, taxable, gst
        for (Order o : placed) {
            BigDecimal[] a = byStore.computeIfAbsent(o.getStore() != null ? o.getStore().getName() : "(no store)", k -> zeros(6));
            a[0] = a[0].add(BigDecimal.ONE);
            if (o.getStatus() == OrderStatus.CANCELLED) { a[1] = a[1].add(BigDecimal.ONE); continue; }
            a[2] = a[2].add(o.getTotal());
            a[3] = a[3].add(o.getRoundOff() == null ? BigDecimal.ZERO : o.getRoundOff());
            Invoice inv = tax.get(o.getId());
            if (inv != null) { a[4] = a[4].add(inv.getTaxableTotal()); a[5] = a[5].add(inv.getTaxTotal()); }
        }
        SXSSFSheet s = wb.createSheet(Section.STORES.title);
        header(s, st, new String[]{"Store", "Orders placed", "Cancelled", "Sales", "Round off", "Taxable value", "GST"},
                new int[]{28, 14, 11, 15, 12, 15, 13});
        int r = 1;
        for (Map.Entry<String, BigDecimal[]> e : byStore.entrySet()) {
            Row row = s.createRow(r++);
            text(row, 0, e.getKey(), null);
            num(row, 1, e.getValue()[0].longValue(), st.integer);
            num(row, 2, e.getValue()[1].longValue(), st.integer);
            for (int i = 2; i <= 5; i++) money(row, i + 1, e.getValue()[i], st.money);
        }
        totals(s, st, r, 1, 6, Set.of(3, 4, 5, 6));
        s.createFreezePane(1, 1);
    }

    private void medicinesSheet(SXSSFWorkbook wb, Styles st, Collection<Invoice> tax) {
        record Key(String name, String hsn, BigDecimal rate) {}
        Map<Key, BigDecimal[]> byMedicine = new HashMap<>();   // packs, value, taxable, gst
        for (Invoice inv : tax) {
            for (Invoice.Line l : inv.getLines()) {
                BigDecimal[] a = byMedicine.computeIfAbsent(new Key(l.getDescription(), l.getHsnCode(), l.getGstRate()), k -> zeros(4));
                a[0] = a[0].add(BigDecimal.valueOf(l.getQuantity()));
                a[1] = a[1].add(l.getTotal());
                a[2] = a[2].add(l.getTaxableValue());
                a[3] = a[3].add(l.getCgst()).add(l.getSgst()).add(l.getIgst());
            }
        }
        SXSSFSheet s = wb.createSheet(Section.MEDICINES.title);
        header(s, st, new String[]{"Medicine", "HSN", "GST rate", "Packs", "Sales (incl. GST)", "Taxable value", "GST"},
                new int[]{34, 10, 10, 9, 17, 15, 13});
        List<Map.Entry<Key, BigDecimal[]>> rows = new ArrayList<>(byMedicine.entrySet());
        rows.sort((x, y) -> y.getValue()[1].compareTo(x.getValue()[1]));   // best sellers first
        int r = 1;
        for (Map.Entry<Key, BigDecimal[]> e : rows) {
            Row row = s.createRow(r++);
            text(row, 0, e.getKey().name(), null);
            text(row, 1, e.getKey().hsn(), null);
            percent(row, 2, e.getKey().rate(), st);
            num(row, 3, e.getValue()[0].longValue(), st.integer);
            money(row, 4, e.getValue()[1], st.money);
            money(row, 5, e.getValue()[2], st.money);
            money(row, 6, e.getValue()[3], st.money);
        }
        totals(s, st, r, 3, 6, Set.of(4, 5, 6));
        s.createFreezePane(1, 1);
    }

    private void hsnSheet(SXSSFWorkbook wb, Styles st, Collection<Invoice> tax) {
        record Key(String hsn, BigDecimal rate) {}
        Map<Key, BigDecimal[]> byHsn = new TreeMap<>(Comparator.comparing(Key::hsn).thenComparing(Key::rate));
        Map<Key, Set<String>> names = new HashMap<>();
        for (Invoice inv : tax) {
            for (Invoice.Line l : inv.getLines()) {
                Key k = new Key(l.getHsnCode() == null ? "" : l.getHsnCode(), l.getGstRate());
                BigDecimal[] a = byHsn.computeIfAbsent(k, x -> zeros(6));   // qty, value, taxable, cgst, sgst, igst
                a[0] = a[0].add(BigDecimal.valueOf(l.getQuantity()));
                a[1] = a[1].add(l.getTotal());
                a[2] = a[2].add(l.getTaxableValue());
                a[3] = a[3].add(l.getCgst());
                a[4] = a[4].add(l.getSgst());
                a[5] = a[5].add(l.getIgst());
                names.computeIfAbsent(k, x -> new TreeSet<>()).add(l.getDescription());
            }
        }
        SXSSFSheet s = wb.createSheet(Section.HSN.title);
        header(s, st, new String[]{"HSN", "Description", "GST rate", "Quantity (packs)", "Total value", "Taxable value", "CGST", "SGST", "IGST"},
                new int[]{11, 40, 10, 16, 14, 15, 12, 12, 12});
        int r = 1;
        for (Map.Entry<Key, BigDecimal[]> e : byHsn.entrySet()) {
            Row row = s.createRow(r++);
            String hsn = e.getKey().hsn();
            text(row, 0, hsn.isEmpty() ? "-" : hsn, null);
            Set<String> n = names.get(e.getKey());
            text(row, 1, n.size() <= 3 ? String.join(", ", n) : String.join(", ", new ArrayList<>(n).subList(0, 3)) + " and " + (n.size() - 3) + " more", null);
            percent(row, 2, e.getKey().rate(), st);
            num(row, 3, e.getValue()[0].longValue(), st.integer);
            for (int i = 1; i <= 5; i++) money(row, 3 + i, e.getValue()[i], st.money);
        }
        totals(s, st, r, 3, 8, Set.of(4, 5, 6, 7, 8));
        s.createFreezePane(1, 1);
    }

    private void returnsSheet(SXSSFWorkbook wb, Styles st, List<ReturnRequest> list) {
        SXSSFSheet s = wb.createSheet(Section.RETURNS.title);
        header(s, st, new String[]{"Requested", "Return no.", "Order no.", "Customer", "Reason", "Status", "Packs", "Refund amount", "Refunded on"},
                new int[]{13, 16, 17, 22, 36, 22, 8, 15, 13});
        int r = 1;
        for (ReturnRequest x : list) {
            Row row = s.createRow(r++);
            date(row, 0, x.getCreatedAt().toLocalDate(), st);
            text(row, 1, x.getReturnNumber(), null);
            text(row, 2, x.getOrder().getOrderNumber(), null);
            text(row, 3, x.getUser().getFullName(), null);
            text(row, 4, x.getReason().getLabel(), null);
            text(row, 5, x.getStatus().getLabel(), null);
            num(row, 6, x.getPackCount(), st.integer);
            money(row, 7, x.getRefundAmount(), st.money);
            if (x.getRefundedAt() != null) date(row, 8, x.getRefundedAt().toLocalDate(), st);
        }
        Row total = s.createRow(r);
        text(total, 0, "Refunded", st.totalLabel);
        formula(total, 7, r == 1 ? "0" : "SUMIF($F$2:$F$" + r + ",\"" + ReturnStatus.REFUNDED.getLabel() + "\",H2:H" + r + ")", st.totalMoney);
        s.setAutoFilter(new CellRangeAddress(0, Math.max(0, r - 1), 0, 8));
        s.createFreezePane(2, 1);
    }

    // ------------------------------------------------------------------ cells

    private static void header(Sheet s, Styles st, String[] heads, int[] widths) {
        Row h = s.createRow(0);
        for (int i = 0; i < heads.length; i++) {
            text(h, i, heads[i], st.header);
            s.setColumnWidth(i, widths[i] * 256);
        }
    }

    /** Totals row: SUM of each column from {@code first} to {@code last}; money columns get the money format. */
    private static void totals(Sheet s, Styles st, int r, int first, int last, Set<Integer> moneyColumns) {
        Row total = s.createRow(r);
        text(total, 0, "Total", st.totalLabel);
        for (int c = first; c <= last; c++) {
            String col = CellReference.convertNumToColString(c);
            formula(total, c, r == 1 ? "0" : "SUM(" + col + "2:" + col + r + ")",
                    moneyColumns.contains(c) ? st.totalMoney : st.totalInteger);
        }
    }

    private static int pair(Sheet s, int r, String label, Object value, Styles st) {
        Row row = s.createRow(r);
        text(row, 0, label, st.label);
        Cell c = row.createCell(1);
        if (value instanceof BigDecimal b) { c.setCellValue(b.doubleValue()); c.setCellStyle(st.money); }
        else if (value instanceof Long n) { c.setCellValue(n); c.setCellStyle(st.integer); }
        else if (value instanceof LocalDate d) { c.setCellValue(d); c.setCellStyle(st.date); }
        else c.setCellValue(String.valueOf(value));
        return r + 1;
    }

    private static void text(Row row, int c, String v, CellStyle style) {
        Cell cell = row.createCell(c);
        if (v != null) cell.setCellValue(v);
        if (style != null) cell.setCellStyle(style);
    }

    private static void num(Row row, int c, long v, CellStyle style) {
        Cell cell = row.createCell(c);
        cell.setCellValue(v);
        cell.setCellStyle(style);
    }

    private static void money(Row row, int c, BigDecimal v, CellStyle style) {
        Cell cell = row.createCell(c);
        cell.setCellValue(v == null ? 0 : v.doubleValue());
        cell.setCellStyle(style);
    }

    private static void percent(Row row, int c, BigDecimal rate, Styles st) {
        Cell cell = row.createCell(c);
        cell.setCellValue(rate.doubleValue() / 100);
        cell.setCellStyle(st.percent);
    }

    private static void date(Row row, int c, LocalDate d, Styles st) {
        Cell cell = row.createCell(c);
        cell.setCellValue(d);
        cell.setCellStyle(st.date);
    }

    private static void formula(Row row, int c, String f, CellStyle style) {
        Cell cell = row.createCell(c);
        cell.setCellFormula(f);
        cell.setCellStyle(style);
    }

    private static BigDecimal sum(Collection<Invoice> tax, java.util.function.Function<Invoice, BigDecimal> f) {
        return tax.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static String capitalize(String s) { return s.charAt(0) + s.substring(1).toLowerCase(); }

    /** Cell styles, created once per workbook (Excel caps how many a file may hold). */
    private static final class Styles {
        final CellStyle title, section, label, muted, header, money, integer, percent, date, month, dateTime,
                totalLabel, totalMoney, totalInteger;

        Styles(Workbook wb) {
            DataFormat fmt = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 15);
            Font grey = wb.createFont();
            grey.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            title = style(wb, big);
            section = style(wb, bold);
            label = wb.createCellStyle();
            muted = style(wb, grey);
            header = style(wb, bold);
            header.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            header.setWrapText(true);
            header.setVerticalAlignment(VerticalAlignment.CENTER);

            String rupees = "\"₹\"#,##0.00";
            money = format(wb, fmt, rupees, null);
            integer = format(wb, fmt, "#,##0", null);
            percent = format(wb, fmt, "0.##%", null);
            date = format(wb, fmt, "dd-mmm-yyyy", null);
            month = format(wb, fmt, "mmm yyyy", null);
            dateTime = format(wb, fmt, "dd-mmm-yyyy hh:mm", null);
            totalLabel = style(wb, bold);
            totalLabel.setBorderTop(BorderStyle.THIN);
            totalMoney = format(wb, fmt, rupees, bold);
            totalMoney.setBorderTop(BorderStyle.THIN);
            totalInteger = format(wb, fmt, "#,##0", bold);
            totalInteger.setBorderTop(BorderStyle.THIN);
        }

        private static CellStyle style(Workbook wb, Font font) {
            CellStyle s = wb.createCellStyle();
            s.setFont(font);
            return s;
        }

        private static CellStyle format(Workbook wb, DataFormat fmt, String pattern, Font font) {
            CellStyle s = wb.createCellStyle();
            s.setDataFormat(fmt.getFormat(pattern));
            if (font != null) s.setFont(font);
            return s;
        }
    }
}
