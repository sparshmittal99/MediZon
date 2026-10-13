package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.DoseLogRepository;
import com.medizon.repository.MedicationPlanRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExpirySweepTest {

    private final MedicationPlanRepository plans = mock(MedicationPlanRepository.class);
    private final DoseLogRepository doseLogs = mock(DoseLogRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final MedicationPlanService service =
            new MedicationPlanService(plans, doseLogs, null, notifications, mock(MemberService.class), mock(AddressService.class));
    private final LocalDate today = LocalDate.of(2026, 10, 12);

    private static MedicationPlan plan(long id, MemberKind kind, int units) {
        Member who = new Member();
        who.setId(id * 10);
        who.setKind(kind);
        who.setName("Asha");
        Medicine m = new Medicine();
        m.setName("Paracetamol 650 mg");
        m.setDosageForm("Tablet");
        MedicationPlan p = new MedicationPlan();
        p.setId(id);
        p.setUser(new User());
        p.setMember(who);
        p.setMedicine(m);
        p.setUnitsRemaining(units);
        p.setExpiryDate(LocalDate.of(2026, 10, 1));
        if (kind != MemberKind.COMMON) p.getReminderTimes().add(LocalTime.of(8, 0));
        return p;
    }

    @Test
    void commonShelfEntryIsRemovedWithAnAlert() {
        MedicationPlan shelf = plan(1, MemberKind.COMMON, 12);
        when(plans.findByExpiryDateBefore(today)).thenReturn(List.of(shelf));

        assertThat(service.clearExpired(today)).isEmpty();

        verify(plans).delete(shelf);
        verify(notifications).notify(any(), eq(NotificationType.EXPIRY_ALERT), eq("Expired: Paracetamol 650 mg (common)"),
                contains("12 tablet(s) of Paracetamol 650 mg (common) expired on 1 Oct 2026"), any(), eq(shelf.getMember()));
    }

    @Test
    void deliveredMedicineWithoutAScheduleIsRemoved() {
        MedicationPlan waiting = plan(2, MemberKind.FAMILY, 20);
        waiting.setSetupRequired(true);
        waiting.setActive(false);
        when(plans.findByExpiryDateBefore(today)).thenReturn(List.of(waiting));

        service.clearExpired(today);

        verify(plans).delete(waiting);
        verify(notifications).notify(any(), eq(NotificationType.EXPIRY_ALERT), anyString(), contains("removed it from your medicines"), any(), any());
    }

    @Test
    void scheduledPlanKeepsRemindersButLosesTheExpiredStock() {
        MedicationPlan active = plan(3, MemberKind.FAMILY, 20);
        when(plans.findByExpiryDateBefore(today)).thenReturn(List.of(active));

        assertThat(service.clearExpired(today)).containsExactly(3L);

        verify(plans, never()).delete(any());
        assertThat(active.getUnitsRemaining()).isZero();
        assertThat(active.getExpiryDate()).isNull();
        assertThat(active.isActive()).isTrue();
        verify(notifications).notify(any(), eq(NotificationType.EXPIRY_ALERT), eq("Expired: Paracetamol 650 mg (Asha)"),
                contains("refill to keep taking it on schedule"), any(), eq(active.getMember()));
    }

    @Test
    void emptyScheduledPlanJustDropsTheDateQuietly() {
        MedicationPlan empty = plan(4, MemberKind.SELF, 0);
        when(plans.findByExpiryDateBefore(today)).thenReturn(List.of(empty));

        service.clearExpired(today);

        assertThat(empty.getExpiryDate()).isNull();
        verifyNoInteractions(notifications);
    }
}
