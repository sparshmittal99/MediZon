package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.DoseLogRepository;
import com.medizon.repository.MedicationPlanRepository;
import com.medizon.repository.NotificationDeviceRepository;
import com.medizon.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The app's alarm list: future doses for the next week, minus answered ones and members this phone doesn't follow. */
class AppScheduleServiceTest {

    private final User user = new User();
    private final LocalDateTime now = LocalDate.of(2026, 11, 5).atTime(9, 0);
    private MedicationPlanRepository plans;
    private DoseLogRepository doseLogs;
    private ReminderRepository reminders;
    private AppScheduleService service;
    private NotificationDevice device;

    private MedicationPlan plan(long id, Member who, LocalTime... times) {
        MedicationPlan p = new MedicationPlan();
        p.setId(id);
        p.setUser(user);
        p.setMember(who);
        p.setCustomName("Thyronorm");
        p.setCustomUnit("tablet(s)");
        p.setStartDate(now.toLocalDate().minusDays(10));
        p.getReminderTimes().addAll(List.of(times));
        return p;
    }

    private static Member member(long id, MemberKind kind) {
        Member m = new Member();
        m.setId(id);
        m.setKind(kind);
        m.setName("Member " + id);
        return m;
    }

    @BeforeEach
    void setUp() {
        plans = mock(MedicationPlanRepository.class);
        doseLogs = mock(DoseLogRepository.class);
        reminders = mock(ReminderRepository.class);
        when(doseLogs.findByPlanUserAndScheduledAtBetween(any(), any(), any())).thenReturn(List.of());
        when(reminders.findByUserAndDoneFalseOrderByDueAtAsc(any())).thenReturn(List.of());
        service = new AppScheduleService(mock(NotificationDeviceRepository.class), plans, doseLogs, reminders,
                mock(MedicationPlanService.class), mock(ReorderService.class), mock(MaintenanceService.class));
        device = new NotificationDevice();
        device.setUser(user);
    }

    @Test
    void listsFutureDosesForAWeek() {
        when(plans.findByUserAndActiveTrue(user)).thenReturn(List.of(plan(1, null, LocalTime.of(8, 0), LocalTime.of(20, 0))));

        List<AppScheduleService.Alarm> alarms = service.schedule(device, now).alarms();

        // today 8:00 has passed: today 20:00, then 6 more days at 8:00 and 20:00
        assertEquals(13, alarms.size());
        AppScheduleService.Alarm first = alarms.get(0);
        assertEquals("DOSE", first.kind());
        assertEquals(1L, first.planId());
        assertTrue(first.at().startsWith("2026-11-05T20:00"), first.at());
        assertEquals("Thyronorm", first.title());
        assertEquals("Take 1 tablet(s)", first.body());
        assertEquals("dose-1-202611052000", first.id());
    }

    @Test
    void skipsAnsweredDoses() {
        MedicationPlan p = plan(1, null, LocalTime.of(20, 0));
        when(plans.findByUserAndActiveTrue(user)).thenReturn(List.of(p));
        DoseLog taken = new DoseLog(p, now.toLocalDate().atTime(20, 0));
        taken.setStatus(DoseStatus.TAKEN);
        when(doseLogs.findByPlanUserAndScheduledAtBetween(any(), any(), any())).thenReturn(List.of(taken));

        List<AppScheduleService.Alarm> alarms = service.schedule(device, now).alarms();

        assertEquals(6, alarms.size());
        assertTrue(alarms.get(0).at().startsWith("2026-11-06T20:00"));
    }

    @Test
    void onlyMembersThisPhoneFollows() {
        Member mom = member(2, MemberKind.FAMILY);
        Member dad = member(3, MemberKind.FAMILY);
        when(plans.findByUserAndActiveTrue(user)).thenReturn(List.of(
                plan(1, mom, LocalTime.of(20, 0)), plan(2, dad, LocalTime.of(21, 0))));
        device.setAllMembers(false);
        device.getMembers().add(mom);

        List<AppScheduleService.Alarm> alarms = service.schedule(device, now).alarms();

        assertTrue(alarms.stream().allMatch(a -> a.planId() == 1L));
    }

    @Test
    void includesOneOffReminders() {
        when(plans.findByUserAndActiveTrue(user)).thenReturn(List.of());
        Reminder r = new Reminder();
        r.setId(5L);
        r.setText("Book a blood test");
        r.setDueAt(now.plusDays(1));
        Reminder past = new Reminder();
        past.setId(6L);
        past.setText("Old");
        past.setDueAt(now.minusHours(1));
        when(reminders.findByUserAndDoneFalseOrderByDueAtAsc(user)).thenReturn(List.of(r, past));

        List<AppScheduleService.Alarm> alarms = service.schedule(device, now).alarms();

        assertEquals(1, alarms.size());
        assertEquals("REMINDER", alarms.get(0).kind());
        assertEquals("Book a blood test", alarms.get(0).body());
    }

    @Test
    void tokensAreStoredHashed() {
        assertEquals(64, AppScheduleService.hash("abc").length());
        assertNotEquals("abc", AppScheduleService.hash("abc"));
    }
}
