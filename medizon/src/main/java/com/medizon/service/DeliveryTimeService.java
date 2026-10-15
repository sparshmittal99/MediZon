package com.medizon.service;

import com.medizon.dto.DeliveryEstimate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Delivery times. Each store delivers to each of its PIN codes in a set number of days (0 to 0 is same-day, see
 * {@link com.medizon.domain.StoreArea}); same-day orders placed after the cutoff go out the next day.
 */
public final class DeliveryTimeService {

    /** Same-day orders must be placed before this time. */
    public static final LocalTime SAME_DAY_CUTOFF = LocalTime.of(18, 0);

    private DeliveryTimeService() {}

    /** When an order placed now arrives, for a store that takes {@code minDays} to {@code maxDays}. */
    public static DeliveryEstimate estimate(int minDays, int maxDays) {
        return estimate(minDays, maxDays, LocalDateTime.now());
    }

    static DeliveryEstimate estimate(int minDays, int maxDays, LocalDateTime now) {
        int min = Math.max(0, minDays);
        int max = Math.max(min, maxDays);
        Duration orderWithin = null;
        if (max == 0) {
            if (now.toLocalTime().isBefore(SAME_DAY_CUTOFF)) {
                orderWithin = Duration.between(now.toLocalTime(), SAME_DAY_CUTOFF);
            } else {
                min = max = 1;   // too late for today: tomorrow
            }
        }
        LocalDate today = now.toLocalDate();
        return new DeliveryEstimate(min, max, today.plusDays(min), today.plusDays(max), orderWithin);
    }
}
