package com.medizon.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodFilterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Test
    void nothingPickedIsThisYear() {
        PeriodFilter f = PeriodFilter.of(null, List.of(), List.of(""), TODAY);
        assertThat(f.isPicked()).isFalse();
        assertThat(f.getLabel()).isEqualTo("2026");
        assertThat(f.getYearParams()).isEmpty();
        assertThat(f.ranges()).singleElement().satisfies(r -> {
            assertThat(r[0]).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
            assertThat(r[1]).isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0));
        });
    }

    @Test
    void yearsMonthsAndDaysMixNewestFirst() {
        PeriodFilter f = PeriodFilter.of(List.of("2024", "2025"), List.of("2026-03"), List.of("2026-10-04", "2026-08-30"), TODAY);
        assertThat(f.getLabel()).isEqualTo("2025, 2024, Mar 2026, 4 Oct 2026, 30 Aug 2026");
        assertThat(f.hasYear(2024)).isTrue();
        assertThat(f.hasMonth("2026-03")).isTrue();
        assertThat(f.hasMonth("2026-04")).isFalse();
        assertThat(f.ranges()).hasSize(5);
        // A single day runs from its midnight to the next
        assertThat(f.ranges()).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo(LocalDateTime.of(2026, 10, 4, 0, 0));
            assertThat(r[1]).isEqualTo(LocalDateTime.of(2026, 10, 5, 0, 0));
        });
        // A month to the first of the next, across a year end too
        PeriodFilter dec = PeriodFilter.of(null, List.of("2025-12"), null, TODAY);
        assertThat(dec.ranges().get(0)[1]).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void valuesThatDontParseAreSkipped() {
        PeriodFilter f = PeriodFilter.of(List.of("20x6", "2025", "2025"), List.of("2026-13"), List.of("yesterday"), TODAY);
        assertThat(f.getYears()).containsExactly(2025);
        assertThat(f.getMonths()).isEmpty();
        assertThat(f.getDates()).isEmpty();
    }

    @Test
    void yearsOfferedRunFromTheFirstToThisYear() {
        PeriodFilter f = PeriodFilter.of(null, null, null, TODAY);
        assertThat(f.yearsSince(2024)).containsExactly(2026, 2025, 2024);
        assertThat(f.yearsSince(null)).containsExactly(2026);
    }

    @Test
    void searchTermIsTrimmedAndEmptyIsNone() {
        assertThat(PeriodFilter.term("  MZ1234 ")).isEqualTo("MZ1234");
        assertThat(PeriodFilter.term("   ")).isNull();
        assertThat(PeriodFilter.term(null)).isNull();
    }
}
