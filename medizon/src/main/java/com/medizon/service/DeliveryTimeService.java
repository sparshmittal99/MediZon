package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.ServiceablePincode;
import com.medizon.dto.DeliveryEstimate;
import com.medizon.repository.MedicineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Delivery times. Each medicine has its own (0 to 0 days is same-day), each PIN code can add days on top, and
 * same-day orders placed after the cutoff go out the next day.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryTimeService {

    public static final int DEFAULT_MIN_DAYS = 3;
    public static final int DEFAULT_MAX_DAYS = 5;
    /** Same-day orders must be placed before this time. */
    public static final LocalTime SAME_DAY_CUTOFF = LocalTime.of(18, 0);

    /** Starting delivery times for medicines that don't have one yet: same day, next day, 1-3, 2-4 or 3-5 days. */
    private static final int[][] STARTING_TIMES = { {0, 0}, {1, 1}, {1, 3}, {2, 4}, {3, 5} };

    private final MedicineRepository medicines;
    private final DeliveryAreaService deliveryAreas;

    /** When this medicine would arrive at a PIN code MediZon delivers to; empty when it doesn't deliver there. */
    public Optional<DeliveryEstimate> estimate(Medicine m, String pincode) {
        Optional<ServiceablePincode> area = deliveryAreas.find(pincode).filter(ServiceablePincode::isActive);
        if (area.isEmpty()) return Optional.empty();
        int extra = Math.max(0, area.get().getExtraDeliveryDays());
        int min = minDays(m) + extra;
        int max = Math.max(min, maxDays(m) + extra);

        LocalDateTime now = LocalDateTime.now();
        Duration orderWithin = null;
        if (max == 0) {
            if (now.toLocalTime().isBefore(SAME_DAY_CUTOFF)) {
                orderWithin = Duration.between(now.toLocalTime(), SAME_DAY_CUTOFF);
            } else {
                min = max = 1;   // too late for today: tomorrow
            }
        }
        LocalDate today = now.toLocalDate();
        return Optional.of(new DeliveryEstimate(min, max, today.plusDays(min), today.plusDays(max), orderWithin));
    }

    public static int minDays(Medicine m) {
        return m.getDeliveryMinDays() != null ? m.getDeliveryMinDays() : DEFAULT_MIN_DAYS;
    }

    public static int maxDays(Medicine m) {
        int max = m.getDeliveryMaxDays() != null ? m.getDeliveryMaxDays() : DEFAULT_MAX_DAYS;
        return Math.max(max, minDays(m));
    }

    /** Fills in a delivery time for the admin form: the default when empty, and "to" never before "from". */
    public static void normalize(Medicine m) {
        int min = minDays(m);
        m.setDeliveryMinDays(min);
        m.setDeliveryMaxDays(maxDays(m));
    }

    /** Medicines without a delivery time (existing catalog, seeded data) get a random starting one; admins change it later. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void assignStartingTimes() {
        List<Medicine> missing = medicines.findAll().stream().filter(m -> m.getDeliveryMinDays() == null).toList();
        if (missing.isEmpty()) return;
        for (Medicine m : missing) {
            int[] t = STARTING_TIMES[ThreadLocalRandom.current().nextInt(STARTING_TIMES.length)];
            m.setDeliveryMinDays(t[0]);
            m.setDeliveryMaxDays(t[1]);
        }
        medicines.saveAll(missing);
        log.info("Gave {} medicine(s) a starting delivery time", missing.size());
    }
}
