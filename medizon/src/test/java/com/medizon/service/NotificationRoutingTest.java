package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.NotificationDeviceRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A device's member choice decides only whose dose alarms ring (and whose dose-time reminders it shows); every other
 * notification, including a dose marked taken or skipped, goes to every device on the account.
 */
class NotificationRoutingTest {

    private static Member member(long id, MemberKind kind, String name) {
        Member m = new Member();
        m.setId(id);
        m.setKind(kind);
        m.setName(name);
        return m;
    }

    private final Member me = member(1, MemberKind.SELF, "Sparsh");
    private final Member mummy = member(2, MemberKind.FAMILY, "Mummy");

    private static NotificationDevice following(long id, Member... members) {
        NotificationDevice d = new NotificationDevice();
        d.setId(id);
        d.setAllMembers(false);
        d.setMembers(Set.of(members));
        d.setPushToken("token-" + id);
        return d;
    }

    private static Notification note(NotificationType type, Member about) {
        Notification n = new Notification();
        n.setType(type);
        n.setMember(about);
        return n;
    }

    @Test
    void onlyDoseRemindersFollowTheMemberChoice() {
        NotificationDevice myPhone = following(10, me);           // set to "Me" only

        assertThat(myPhone.shows(note(NotificationType.DOSE_REMINDER, mummy))).isFalse();
        assertThat(myPhone.shows(note(NotificationType.DOSE_REMINDER, me))).isTrue();
        // Everything else about Mummy still comes to this phone
        for (NotificationType t : NotificationType.values()) {
            if (t == NotificationType.DOSE_REMINDER) continue;
            assertThat(myPhone.shows(note(t, mummy))).as(t.name()).isTrue();
        }
        // Alarms still ring only for the members it follows
        assertThat(myPhone.allows(mummy)).isFalse();
        assertThat(myPhone.allows(me)).isTrue();
    }

    @Test
    void aMarkedDoseIsWordedAsThePhoneWordsItsOwn() {
        MedicationPlan plan = new MedicationPlan();
        plan.setId(7L);
        plan.setCustomName("Metformin");
        plan.setCustomUnit("tablet");
        plan.setMember(mummy);
        LocalDateTime now = LocalDateTime.of(2026, 11, 9, 9, 5);

        Map<String, String> taken = AppSyncSignal.doseMarked(plan, now.withMinute(0), DoseStatus.TAKEN, now);
        assertThat(taken.get("title")).isEqualTo("Dose taken · Metformin");
        assertThat(taken.get("body")).isEqualTo("1 tablet of Metformin was taken by Mummy at 9:05 AM. Scheduled for 9:00 AM.");
        assertThat(taken.get("link")).isEqualTo("/plans?member=2");

        // The account holder's own dose, from yesterday evening, skipped
        plan.setMember(me);
        Map<String, String> skipped = AppSyncSignal.doseMarked(plan, LocalDateTime.of(2026, 11, 8, 21, 0), DoseStatus.SKIPPED, now);
        assertThat(skipped.get("title")).isEqualTo("Dose skipped · Metformin");
        assertThat(skipped.get("body")).isEqualTo("1 tablet of Metformin was skipped by you at 9:05 AM. Scheduled for 8 Nov, 9:00 PM.");
    }

    @Test
    void everyPhoneHearsAboutAMarkedDoseIncludingTheOneThatAnswered() {
        PushService push = mock(PushService.class);
        when(push.isEnabled()).thenReturn(true);
        NotificationDeviceRepository devices = mock(NotificationDeviceRepository.class);
        User user = new User();
        user.setId(1L);
        NotificationDevice mummysPhone = following(21, mummy);     // answered the alarm
        NotificationDevice myPhone = following(22, me);            // doesn't follow Mummy
        when(devices.findByUserAndPushTokenIsNotNull(user)).thenReturn(List.of(mummysPhone, myPhone));

        MedicationPlan plan = new MedicationPlan();
        plan.setId(7L);
        plan.setCustomName("Metformin");
        plan.setMember(mummy);
        new AppSyncSignal(push, devices).doseAnswered(user, plan, LocalDateTime.now(), DoseStatus.TAKEN);

        // Both phones stop ringing and show "Dose taken" (on Mummy's it replaces the one it showed, quietly)
        for (String token : List.of("token-21", "token-22")) {
            verify(push).send(eq(token), argThat(d -> "DOSE_DONE".equals(d.get("type"))
                    && "Dose taken · Metformin".equals(d.get("title"))));
        }
    }
}
