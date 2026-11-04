package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.MedicationPlanRepository;
import com.medizon.repository.OrderRepository;
import com.medizon.repository.PrescriptionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A medicine typed in as "Other" gets the low-supply alert, once, and is never ordered. */
class OtherMedicineAlertTest {

    private final MedicationPlanRepository plans = mock(MedicationPlanRepository.class);
    private final OrderService orderService = mock(OrderService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ReorderService service = new ReorderService(plans, mock(OrderRepository.class),
            mock(PrescriptionRepository.class), orderService, notifications, null);

    private MedicationPlan plan(ReorderMode mode, int unitsLeft) {
        MedicationPlan p = new MedicationPlan();
        p.setId(9L);
        p.setUser(new User());
        p.setCustomName("Thyronorm 50 mcg");
        p.setCustomUnit("tablet(s)");
        p.getReminderTimes().add(LocalTime.of(8, 0));
        p.setStartDate(LocalDate.now().minusDays(5));
        p.setUnitsRemaining(unitsLeft);
        p.setReorderThresholdDays(5);
        p.setReorderMode(mode);
        when(plans.findById(9L)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void runningLowAlertsOnceAndNeverOrders() {
        MedicationPlan p = plan(ReorderMode.MANUAL, 3);          // 3 tablets, 1 a day: 3 days < 5

        service.evaluate(9L);
        service.evaluate(9L);

        verify(notifications, times(1)).notify(any(), eq(NotificationType.REORDER_ALERT), eq("Running low on Thyronorm 50 mcg"),
                argThat(m -> m.contains("Buy more")), anyString(), any());
        verifyNoInteractions(orderService);
        assertTrue(p.isReorderPending());
    }

    @Test
    void plentyLeftOrAlertOffSaysNothing() {
        plan(ReorderMode.MANUAL, 60);
        service.evaluate(9L);
        plan(ReorderMode.OFF, 2);
        service.evaluate(9L);
        verifyNoInteractions(notifications, orderService);
    }
}
