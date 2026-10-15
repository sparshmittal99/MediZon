package com.medizon.service;

import com.medizon.dto.ReportPeriod;
import com.medizon.dto.SalesDay;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Date-wise and month-wise sales, orders and returns for the admin. Aggregated in SQL so long ranges stay cheap.
 * Orders and sales count on the day an order was placed; returns on the day they were requested;
 * refunds on the day they were paid out.
 */
@Service
@RequiredArgsConstructor
public class SalesReportService {

    /** Longest custom range: three years. */
    public static final int MAX_DAYS = 1096;
    /** Longest range shown day by day; longer ones are shown month by month. */
    public static final int MAX_DAILY_DAYS = 366;
    private static final LocalDate BEGINNING = LocalDate.of(2000, 1, 1);
    private static final Pattern FY = Pattern.compile("(\\d{4})-(\\d{2})");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ periods

    /**
     * Works out the report period from the form: {@code kind} is "range", "month" or "fy". Missing or invalid values
     * fall back to sensible defaults (the last 30 days, this month, this financial year); nothing runs past today.
     */
    public ReportPeriod resolve(String kind, LocalDate from, LocalDate to, YearMonth month, String fy) {
        LocalDate today = LocalDate.now();
        if ("fy".equals(kind)) {
            int start = fyStart(fy);
            if (start > fyStart(financialYear(today))) start = fyStart(financialYear(today));
            String name = start + "-" + String.format("%02d", (start + 1) % 100);
            LocalDate end = LocalDate.of(start + 1, 3, 31);
            boolean running = end.isAfter(today);
            return new ReportPeriod(ReportPeriod.Kind.FY, LocalDate.of(start, 4, 1), running ? today : end,
                    "FY " + name + (running ? " (to date)" : ""), name, null, running);
        }
        if ("month".equals(kind)) {
            YearMonth m = month == null || month.isAfter(YearMonth.from(today)) ? YearMonth.from(today) : month;
            boolean running = m.equals(YearMonth.from(today));
            return new ReportPeriod(ReportPeriod.Kind.MONTH, m.atDay(1), running ? today : m.atEndOfMonth(),
                    m.format(MONTH) + (running ? " (to date)" : ""), null, m, running);
        }
        LocalDate t = to == null || to.isAfter(today) ? today : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        if (f.isAfter(t)) { LocalDate x = f; f = t; t = x; }
        if (f.isBefore(t.minusDays(MAX_DAYS - 1))) f = t.minusDays(MAX_DAYS - 1);
        String label = f.equals(t) ? f.format(DAY) : f.format(DAY) + " to " + t.format(DAY);
        return new ReportPeriod(ReportPeriod.Kind.RANGE, f, t, label, null, null, false);
    }

    /** "2026-27" for any day from 1 April 2026 to 31 March 2027. */
    public static String financialYear(LocalDate day) {
        int start = day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    private static int fyStart(String fy) {
        if (fy != null) {
            Matcher m = FY.matcher(fy.trim());
            if (m.matches()) {
                int start = Integer.parseInt(m.group(1));
                if ((start + 1) % 100 == Integer.parseInt(m.group(2))) return start;
            }
        }
        return fyStart(financialYear(LocalDate.now()));
    }

    /** Financial years with orders, newest first; always includes the current one. */
    public List<String> financialYears() {
        LocalDate first = Optional.ofNullable(jdbc.queryForObject("select min(created_at) from orders", Timestamp.class))
                .map(ts -> ts.toLocalDateTime().toLocalDate()).orElse(LocalDate.now());
        List<String> out = new ArrayList<>();
        for (int y = fyStart(financialYear(LocalDate.now())); y >= fyStart(financialYear(first)); y--) {
            out.add(y + "-" + String.format("%02d", (y + 1) % 100));
        }
        return out;
    }

    // ------------------------------------------------------------------ rows

    /** One row per day, oldest first, including days with nothing. */
    public List<SalesDay> daily(LocalDate from, LocalDate to) {
        Map<LocalDate, SalesDay> days = collect(from, to);
        List<SalesDay> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) out.add(days.getOrDefault(d, new SalesDay(d)));
        return out;
    }

    /** One row per calendar month touching the range, oldest first; partial months only count days in the range. */
    public List<SalesDay> monthly(LocalDate from, LocalDate to) {
        Map<YearMonth, SalesDay> months = new LinkedHashMap<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) months.put(m, new SalesDay(m));
        collect(from, to).forEach((day, row) -> months.get(YearMonth.from(day)).add(row));
        return new ArrayList<>(months.values());
    }

    public SalesDay day(LocalDate day) { return collect(day, day).getOrDefault(day, new SalesDay(day)); }

    public SalesDay allTime() { return SalesDay.total(collect(BEGINNING, LocalDate.now().plusDays(1)).values()); }

    private Map<LocalDate, SalesDay> collect(LocalDate from, LocalDate to) {
        Date start = Date.valueOf(from), end = Date.valueOf(to.plusDays(1));
        Map<LocalDate, SalesDay> days = new TreeMap<>();

        jdbc.query("""
                select cast(created_at as date) as day, count(*) as orders,
                       count(*) filter (where status = 'CANCELLED') as cancelled,
                       coalesce(sum(total) filter (where status <> 'CANCELLED'), 0) as sales,
                       coalesce(sum(round_off) filter (where status <> 'CANCELLED'), 0) as round_off
                from orders where created_at >= ? and created_at < ? group by 1""",
                rs -> {
                    SalesDay d = days.computeIfAbsent(rs.getDate("day").toLocalDate(), SalesDay::new);
                    d.setOrders(rs.getLong("orders"));
                    d.setCancelled(rs.getLong("cancelled"));
                    d.setSales(rs.getBigDecimal("sales"));
                    d.setRoundOff(rs.getBigDecimal("round_off"));
                }, start, end);

        jdbc.query("""
                select cast(o.created_at as date) as day, coalesce(sum(i.quantity), 0) as packs
                from order_item i join orders o on o.id = i.order_id
                where o.status <> 'CANCELLED' and o.created_at >= ? and o.created_at < ? group by 1""",
                rs -> { days.computeIfAbsent(rs.getDate("day").toLocalDate(), SalesDay::new).setPacks(rs.getLong("packs")); },
                start, end);

        jdbc.query("""
                select cast(created_at as date) as day, count(*) as returns
                from return_requests where created_at >= ? and created_at < ? group by 1""",
                rs -> { days.computeIfAbsent(rs.getDate("day").toLocalDate(), SalesDay::new).setReturns(rs.getLong("returns")); },
                start, end);

        jdbc.query("""
                select cast(refunded_at as date) as day, count(*) as refunds, coalesce(sum(refund_amount), 0) as amount
                from return_requests where status = 'REFUNDED' and refunded_at >= ? and refunded_at < ? group by 1""",
                rs -> {
                    SalesDay d = days.computeIfAbsent(rs.getDate("day").toLocalDate(), SalesDay::new);
                    d.setRefundCount(rs.getLong("refunds"));
                    d.setRefunds(rs.getBigDecimal("amount"));
                }, start, end);
        return days;
    }

    // ------------------------------------------------------------------ CSV

    /** The table as CSV, oldest first, for spreadsheets and accounts. */
    public String csv(List<SalesDay> rows, SalesDay total) {
        StringBuilder out = new StringBuilder((rows.isEmpty() || !rows.get(0).isMonthly() ? "Date" : "Month")
                + ",Orders placed,Cancelled,Packs sold,Sales (INR),Round off (INR),Returns requested,Refunds paid,Refunded (INR),Net revenue (INR)\n");
        rows.forEach(d -> line(out, d.isMonthly() ? d.getMonth().toString() : d.getDay().toString(), d));
        line(out, "Total", total);
        return out.toString();
    }

    private static void line(StringBuilder out, String label, SalesDay d) {
        out.append(String.join(",", label, String.valueOf(d.getOrders()), String.valueOf(d.getCancelled()),
                String.valueOf(d.getPacks()), money(d.getSales()), money(d.getRoundOff()), String.valueOf(d.getReturns()),
                String.valueOf(d.getRefundCount()), money(d.getRefunds()), money(d.getNet()))).append('\n');
    }

    private static String money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
}
