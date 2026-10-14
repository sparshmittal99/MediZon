package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.ServiceablePincode;
import com.medizon.dto.DeliveryEstimate;
import com.medizon.repository.MedicineRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DeliveryTimeTest {

    private final DeliveryAreaService areas = mock(DeliveryAreaService.class);
    private final DeliveryTimeService service = new DeliveryTimeService(mock(MedicineRepository.class), areas);

    private static Medicine medicine(Integer min, Integer max) {
        Medicine m = new Medicine();
        m.setDeliveryMinDays(min);
        m.setDeliveryMaxDays(max);
        return m;
    }

    private void area(String pin, int extraDays, boolean active) {
        ServiceablePincode p = new ServiceablePincode();
        p.setPincode(pin);
        p.setExtraDeliveryDays(extraDays);
        p.setActive(active);
        when(areas.find(pin)).thenReturn(Optional.of(p));
    }

    @Test
    void productDaysPlusPincodeDays() {
        area("400050", 1, true);
        DeliveryEstimate e = service.estimate(medicine(3, 5), "400050").orElseThrow();
        assertThat(e.getMinDays()).isEqualTo(4);
        assertThat(e.getMaxDays()).isEqualTo(6);
        assertThat(e.getFrom()).isEqualTo(LocalDate.now().plusDays(4));
        assertThat(e.getTo()).isEqualTo(LocalDate.now().plusDays(6));
        assertThat(e.getHeadline()).isEqualTo("Delivery in 4–6 days");
    }

    @Test
    void sameDayBeforeTheCutoffOtherwiseTomorrow() {
        area("400050", 0, true);
        DeliveryEstimate e = service.estimate(medicine(0, 0), "400050").orElseThrow();
        if (LocalTime.now().isBefore(DeliveryTimeService.SAME_DAY_CUTOFF)) {
            assertThat(e.isSameDay()).isTrue();
            assertThat(e.getHeadline()).isEqualTo("Same-day delivery");
            assertThat(e.getOrderWithinText()).startsWith("Order within ");
        } else {
            assertThat(e.getHeadline()).isEqualTo("Delivery tomorrow");
            assertThat(e.getOrderWithinText()).isNull();
        }
    }

    @Test
    void extraPincodeDaysEndSameDay() {
        area("400701", 2, true);
        DeliveryEstimate e = service.estimate(medicine(0, 0), "400701").orElseThrow();
        assertThat(e.isSameDay()).isFalse();
        assertThat(e.getHeadline()).isEqualTo("Delivery in 2 days");
    }

    @Test
    void noEstimateWhereWeDontDeliver() {
        area("110001", 0, false);
        when(areas.find("999999")).thenReturn(Optional.empty());
        assertThat(service.estimate(medicine(1, 2), "110001")).isEmpty();
        assertThat(service.estimate(medicine(1, 2), "999999")).isEmpty();
    }

    @Test
    void missingOrBackwardsTimesAreFixedOnSave() {
        Medicine fresh = medicine(null, null);
        DeliveryTimeService.normalize(fresh);
        assertThat(fresh.getDeliveryMinDays()).isEqualTo(3);
        assertThat(fresh.getDeliveryMaxDays()).isEqualTo(5);

        Medicine backwards = medicine(4, 2);
        DeliveryTimeService.normalize(backwards);
        assertThat(backwards.getDeliveryMaxDays()).isEqualTo(4);
    }
}
