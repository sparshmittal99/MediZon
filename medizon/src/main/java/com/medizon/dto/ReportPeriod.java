package com.medizon.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/** The period a sales report covers: a custom date range, one month, or an Indian financial year (April to March). */
@Getter
@AllArgsConstructor
public class ReportPeriod {

    public enum Kind { RANGE, MONTH, FY }

    private final Kind kind;
    private final LocalDate from;
    /** Last day included; never after today. */
    private final LocalDate to;
    /** "FY 2026-27", "September 2026" or "1 Sep 2026 to 12 Oct 2026". */
    private final String label;
    /** Set for {@link Kind#FY}: "2026-27". */
    private final String fy;
    /** Set for {@link Kind#MONTH}. */
    private final YearMonth month;
    /** The month or financial year is still running, so the report runs to today. */
    private final boolean toDate;

    public long getDays() { return ChronoUnit.DAYS.between(from, to) + 1; }

    /** For export file names: "FY2026-27", "2026-09", "2026-09-01_to_2026-10-12". */
    public String getFileSuffix() {
        return switch (kind) {
            case FY -> "FY" + fy;
            case MONTH -> month.toString();
            case RANGE -> from + "_to_" + to;
        };
    }

    public boolean isSingleDay() { return from.equals(to); }
}
