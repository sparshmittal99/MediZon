package com.medizon.service;

import com.medizon.dto.ReportPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPeriodTest {

    private final SalesReportService service = new SalesReportService(null);
    private final LocalDate today = LocalDate.now();
    private final String thisFy = SalesReportService.financialYear(today);
    private final int thisFyStart = Integer.parseInt(thisFy.substring(0, 4));

    @Test
    void pastFinancialYearRunsAprilToMarch() {
        String last = (thisFyStart - 1) + "-" + String.format("%02d", thisFyStart % 100);
        ReportPeriod p = service.resolve("fy", null, null, null, last);
        assertThat(p.getFrom()).isEqualTo(LocalDate.of(thisFyStart - 1, 4, 1));
        assertThat(p.getTo()).isEqualTo(LocalDate.of(thisFyStart, 3, 31));
        assertThat(p.getLabel()).isEqualTo("FY " + last);
        assertThat(p.isToDate()).isFalse();
        assertThat(p.getFileSuffix()).isEqualTo("FY" + last);
    }

    @Test
    void currentFinancialYearRunsToToday() {
        ReportPeriod p = service.resolve("fy", null, null, null, thisFy);
        assertThat(p.getFrom()).isEqualTo(LocalDate.of(thisFyStart, 4, 1));
        assertThat(p.getTo()).isEqualTo(today);
        assertThat(p.getLabel()).isEqualTo("FY " + thisFy + " (to date)");
    }

    @Test
    void invalidOrFutureFinancialYearFallsBackToTheCurrentOne() {
        assertThat(service.resolve("fy", null, null, null, "2025-27").getFy()).isEqualTo(thisFy);
        assertThat(service.resolve("fy", null, null, null, "nonsense").getFy()).isEqualTo(thisFy);
        String future = (thisFyStart + 3) + "-" + String.format("%02d", (thisFyStart + 4) % 100);
        assertThat(service.resolve("fy", null, null, null, future).getFy()).isEqualTo(thisFy);
    }

    @Test
    void financialYearFromAnyDate() {
        assertThat(SalesReportService.financialYear(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
        assertThat(SalesReportService.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(SalesReportService.financialYear(LocalDate.of(2099, 12, 1))).isEqualTo("2099-00");
    }

    @Test
    void monthCoversTheWholeMonthOrToToday() {
        ReportPeriod feb = service.resolve("month", null, null, YearMonth.of(2024, 2), null);
        assertThat(feb.getFrom()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(feb.getTo()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(feb.getLabel()).isEqualTo("February 2024");
        assertThat(feb.getFileSuffix()).isEqualTo("2024-02");

        ReportPeriod now = service.resolve("month", null, null, YearMonth.from(today).plusMonths(2), null);
        assertThat(now.getMonth()).isEqualTo(YearMonth.from(today));
        assertThat(now.getTo()).isEqualTo(today);
        assertThat(now.isToDate()).isTrue();
    }

    @Test
    void rangesAreSwappedClampedAndDefaulted() {
        ReportPeriod def = service.resolve("range", null, null, null, null);
        assertThat(def.getDays()).isEqualTo(30);
        assertThat(def.getTo()).isEqualTo(today);

        ReportPeriod swapped = service.resolve("range", today, today.minusDays(4), null, null);
        assertThat(swapped.getFrom()).isEqualTo(today.minusDays(4));
        assertThat(swapped.getTo()).isEqualTo(today);

        ReportPeriod future = service.resolve("range", today.minusDays(1), today.plusDays(9), null, null);
        assertThat(future.getTo()).isEqualTo(today);

        ReportPeriod huge = service.resolve("range", today.minusYears(10), today, null, null);
        assertThat(huge.getDays()).isEqualTo(SalesReportService.MAX_DAYS);

        assertThat(service.resolve(null, today, today, null, null).getLabel())
                .isEqualTo(service.resolve("range", today, today, null, null).getLabel());
    }
}
