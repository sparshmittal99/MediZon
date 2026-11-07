package com.medizon.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HealthQuotesTest {

    @Test
    void noQuoteComesBackUntilAllHaveBeenSent() {
        LocalDate start = LocalDate.of(2026, 11, 7);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < HealthQuotes.count(); i++) {
            assertThat(seen.add(HealthQuotes.forDay(start.plusDays(i)))).as("day %d repeats a quote", i).isTrue();
        }
        assertThat(HealthQuotes.count()).isGreaterThanOrEqualTo(100);
        // Then the cycle starts again, still never the same two days running
        assertThat(HealthQuotes.forDay(start.plusDays(HealthQuotes.count()))).isEqualTo(HealthQuotes.forDay(start));
    }

    @Test
    void eachDayHasItsOwnTimeBetween7amAnd9pm() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Set<LocalTime> times = new HashSet<>();
        for (int i = 0; i < 365; i++) {
            LocalDate day = start.plusDays(i);
            LocalTime t = HealthQuotes.sendTime(day);
            assertThat(t).isAfterOrEqualTo(LocalTime.of(7, 0)).isBefore(LocalTime.of(21, 0));
            assertThat(HealthQuotes.sendTime(day)).isEqualTo(t);   // the same after a restart
            times.add(t);
        }
        assertThat(times.size()).isGreaterThan(200);   // spread over the day, not one fixed time
    }
}
