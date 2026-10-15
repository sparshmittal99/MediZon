package com.medizon.service;

import com.medizon.dto.DeliveryEstimate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryTimeTest {

    private static final LocalDateTime MORNING = LocalDate.of(2026, 10, 15).atTime(10, 30);
    private static final LocalDateTime EVENING = LocalDate.of(2026, 10, 15).atTime(20, 0);

    @Test
    void theStoresDaysForThePincode() {
        DeliveryEstimate e = DeliveryTimeService.estimate(2, 4, MORNING);
        assertThat(e.getFrom()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(e.getTo()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(e.getHeadline()).isEqualTo("Delivery in 2–4 days");
    }

    @Test
    void sameDayBeforeTheCutoffOtherwiseTomorrow() {
        DeliveryEstimate today = DeliveryTimeService.estimate(0, 0, MORNING);
        assertThat(today.isSameDay()).isTrue();
        assertThat(today.getHeadline()).isEqualTo("Same-day delivery");
        assertThat(today.getOrderWithinText()).isEqualTo("Order within 7 hrs 30 mins");

        DeliveryEstimate late = DeliveryTimeService.estimate(0, 0, EVENING);
        assertThat(late.getHeadline()).isEqualTo("Delivery tomorrow");
        assertThat(late.getOrderWithinText()).isNull();
        assertThat(DeliveryTimeService.SAME_DAY_CUTOFF).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void backwardsTimesAreTreatedAsTheLongerOne() {
        DeliveryEstimate e = DeliveryTimeService.estimate(3, 1, MORNING);
        assertThat(e.getMinDays()).isEqualTo(3);
        assertThat(e.getMaxDays()).isEqualTo(3);
    }
}
