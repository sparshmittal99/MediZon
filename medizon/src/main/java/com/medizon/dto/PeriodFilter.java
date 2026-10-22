package com.medizon.dto;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The date filter on the Orders and Returns pages: any mix of whole years, months of a given year and single days,
 * matching a row placed in any of them. With nothing picked it is the current year. Lists show the newest
 * {@link #LIMIT} rows that match.
 */
public class PeriodFilter {

    /** Most rows an Orders or Returns list shows, newest first. */
    public static final int LIMIT = 20;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final SortedSet<Integer> years;
    private final SortedSet<YearMonth> months;
    private final SortedSet<LocalDate> dates;
    private final int thisYear;

    private PeriodFilter(SortedSet<Integer> years, SortedSet<YearMonth> months, SortedSet<LocalDate> dates, int thisYear) {
        this.years = years;
        this.months = months;
        this.dates = dates;
        this.thisYear = thisYear;
    }

    /** Reads the page's year, month (yyyy-MM) and date (yyyy-MM-dd) parameters; values that don't parse are skipped. */
    public static PeriodFilter of(List<String> year, List<String> month, List<String> date) {
        return of(year, month, date, LocalDate.now());
    }

    static PeriodFilter of(List<String> year, List<String> month, List<String> date, LocalDate today) {
        return new PeriodFilter(parse(year, Integer::valueOf), parse(month, YearMonth::parse), parse(date, LocalDate::parse),
                today.getYear());
    }

    private static <T extends Comparable<? super T>> SortedSet<T> parse(List<String> values, Function<String, T> parser) {
        SortedSet<T> out = new TreeSet<>(Comparator.reverseOrder());   // newest first
        if (values == null) return out;
        for (String v : values) {
            if (v == null || v.isBlank()) continue;
            try {
                out.add(parser.apply(v.trim()));
            } catch (NumberFormatException | DateTimeParseException e) {
                // a hand-edited URL: ignore that value
            }
        }
        return out;
    }

    /** Whether anything was picked; otherwise the list is this year's. */
    public boolean isPicked() { return !years.isEmpty() || !months.isEmpty() || !dates.isEmpty(); }

    public List<Integer> getYears() { return List.copyOf(years); }

    public List<YearMonth> getMonths() { return List.copyOf(months); }

    public List<LocalDate> getDates() { return List.copyOf(dates); }

    /** The values as they go back into links (empty when nothing was picked, so links stay on the default). */
    public List<String> getYearParams() { return years.stream().map(String::valueOf).toList(); }

    public List<String> getMonthParams() { return months.stream().map(YearMonth::toString).toList(); }

    public List<String> getDateParams() { return dates.stream().map(LocalDate::toString).toList(); }

    public int getThisYear() { return thisYear; }

    public boolean hasYear(int y) { return years.contains(y); }

    public boolean hasMonth(String yearMonth) { return months.stream().anyMatch(m -> m.toString().equals(yearMonth)); }

    /** "2026", or "2025, Mar 2026, 4 Oct 2026" for what was picked. */
    public String getLabel() {
        if (!isPicked()) return String.valueOf(thisYear);
        return Stream.of(years.stream().map(String::valueOf), months.stream().map(MONTH::format), dates.stream().map(DAY::format))
                .flatMap(s -> s).collect(Collectors.joining(", "));
    }

    /** The periods as [from, to) ranges of time. */
    List<LocalDateTime[]> ranges() {
        List<LocalDateTime[]> out = new ArrayList<>();
        if (!isPicked()) {
            out.add(range(LocalDate.of(thisYear, 1, 1), LocalDate.of(thisYear + 1, 1, 1)));
            return out;
        }
        years.forEach(y -> out.add(range(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1))));
        months.forEach(m -> out.add(range(m.atDay(1), m.plusMonths(1).atDay(1))));
        dates.forEach(d -> out.add(range(d, d.plusDays(1))));
        return out;
    }

    private static LocalDateTime[] range(LocalDate from, LocalDate to) {
        return new LocalDateTime[]{from.atStartOfDay(), to.atStartOfDay()};
    }

    /** The search box's text, trimmed; null when empty. */
    public static String term(String search) {
        return search == null || search.isBlank() ? null : search.trim();
    }

    /**
     * Rows whose {@code numberField} contains the search text (at any date) when there is one; otherwise rows whose
     * {@code dateField} falls in one of the periods.
     */
    public <T> Specification<T> matching(String dateField, String numberField, String search) {
        String term = term(search);
        if (term != null) {
            String like = "%" + term.toUpperCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            return (root, query, cb) -> cb.like(cb.upper(root.get(numberField)), like, '\\');
        }
        List<LocalDateTime[]> ranges = ranges();
        return (root, query, cb) -> cb.or(ranges.stream()
                .map(r -> cb.and(cb.greaterThanOrEqualTo(root.get(dateField), r[0]), cb.lessThan(root.get(dateField), r[1])))
                .toArray(Predicate[]::new));
    }

    /** The first {@link #LIMIT} rows, newest first. */
    public static Pageable newest() {
        return PageRequest.of(0, LIMIT, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    /** Years offered in the filter: this year back to the first year with anything in it, or to any year picked. */
    public List<Integer> yearsSince(Integer firstYear) {
        int from = firstYear == null ? thisYear : Math.min(firstYear, thisYear);
        for (int y : years) from = Math.min(from, y);
        for (YearMonth m : months) from = Math.min(from, m.getYear());
        List<Integer> out = new ArrayList<>();
        for (int y = thisYear; y >= from; y--) out.add(y);
        return out;
    }
}
