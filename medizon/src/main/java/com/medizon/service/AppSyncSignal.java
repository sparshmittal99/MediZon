package com.medizon.service;

import com.medizon.domain.DoseStatus;
import com.medizon.domain.MedicationPlan;
import com.medizon.domain.Member;
import com.medizon.domain.NotificationDevice;
import com.medizon.domain.User;
import com.medizon.repository.NotificationDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tells the user's Android phones to fetch their dose alarms now (a silent Firebase "SYNC" message), whenever a
 * medicine's schedule or a one-off reminder changes. Without it a new dose time only reached the phone at its next
 * periodic sync, which could be after the dose.
 */
@Service
@RequiredArgsConstructor
public class AppSyncSignal {

    private final PushService push;
    private final NotificationDeviceRepository devices;

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    public void scheduleChanged(User user) {
        sendAfterCommit(user, Map.of("type", "SYNC"));
    }

    /**
     * A dose was marked taken or skipped (on a phone, the website or by the assistant): every phone signed in to the
     * account, the one that answered included, stops ringing for it and shows "Dose taken · Metformin" right away,
     * whichever members it follows. {@code at} matches the alarm's own time.
     */
    public void doseAnswered(User user, MedicationPlan plan, LocalDateTime at, DoseStatus status) {
        Map<String, String> data = new HashMap<>(Map.of("type", "DOSE_DONE", "planId", String.valueOf(plan.getId()),
                "at", at.atZone(ZoneId.systemDefault()).format(AT)));
        data.putAll(doseMarked(plan, at, status, LocalDateTime.now()));
        sendAfterCommit(user, data);
    }

    /**
     * A dose is still unmarked 30 minutes after its time: wake the phones (high-priority push) so each runs its own
     * missed-dose check now. The phone's local 30-minute alarm alone can be held back by Android for up to an hour.
     */
    public void doseCheck(User user, MedicationPlan plan, LocalDateTime at) {
        sendAfterCommit(user, Map.of("type", "DOSE_CHECK", "planId", String.valueOf(plan.getId()),
                "at", at.atZone(ZoneId.systemDefault()).format(AT)));
    }

    /** A backup ring is pointless once the dose is half an hour old: the phone shows "missed" instead by then. */
    static final String DOSE_DUE_TTL = "1800s";

    /**
     * A dose is due now: a backup for the phone's own alarm, which Android can delete (after a restart the app
     * wasn't allowed to start on, or a battery saver). Sent only to phones that ring for this member, with the same
     * id and text as the alarm on their schedule; a phone that already rang it from its own alarm ignores it, one
     * that didn't rings now. Its ring is a visible alarm, so Firebase keeps these high priority.
     */
    public void doseDue(MedicationPlan plan, LocalDateTime at) {
        if (!AppScheduleService.ringsOnPhones(plan)) return;
        Map<String, String> data = Map.of(
                "type", "DOSE_DUE",
                "id", AppScheduleService.doseAlarmId(plan, at),
                "planId", String.valueOf(plan.getId()),
                "at", at.atZone(ZoneId.systemDefault()).format(AT),
                "title", plan.getTitle(),
                "body", AppScheduleService.doseBody(plan),
                "path", MedicationPlanService.planLink(plan));
        // Only app versions that handle it: an older one would show it as a plain notification on top of its alarm
        sendAfterCommit(plan.getUser(), d -> d.handlesDoseBackup() && d.allows(plan.getMember()) ? data : null, DOSE_DUE_TTL);
    }

    private static final DateTimeFormatter TIME =DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    /**
     * The phone notification for a marked dose, worded as the app words its own after Taken / Skip:
     * "Dose taken · Metformin" / "1 tablet of Metformin was taken by Asha at 9:05 AM. Scheduled for 9:00 AM."
     */
    static Map<String, String> doseMarked(MedicationPlan plan, LocalDateTime at, DoseStatus status, LocalDateTime now) {
        boolean taken = status != DoseStatus.SKIPPED;
        String name = plan.getMedicineName();
        Member m = plan.getMember();
        String suffix = m == null ? "" : m.getSuffix().trim();
        String by = suffix.isEmpty() ? "you" : suffix.replaceAll("^\\(|\\)$", "");    // " (Asha)" -> "Asha"
        String scheduled = at.toLocalDate().equals(now.toLocalDate()) ? at.format(TIME) : at.format(DAY_TIME);
        return Map.of(
                "title", (taken ? "Dose taken" : "Dose skipped") + " · " + name,
                "body", plan.getDoseLabel() + " of " + name + " was " + (taken ? "taken" : "skipped") + " by " + by
                        + " at " + now.format(TIME) + ". Scheduled for " + scheduled + ".",
                "link", MedicationPlanService.planLink(plan));
    }

    private void sendAfterCommit(User user, Map<String, String> data) {
        sendAfterCommit(user, d -> data, null);
    }

    /** {@code dataFor} gives each phone its message, or null to skip that phone; {@code ttl} null for the default. */
    private void sendAfterCommit(User user, java.util.function.Function<NotificationDevice, Map<String, String>> dataFor, String ttl) {
        if (user == null || user.getId() == null || !push.isEnabled()) return;
        List<NotificationDevice> phones = devices.findByUserAndPushTokenIsNotNull(user);
        if (phones.isEmpty()) return;
        List<Map.Entry<String, Map<String, String>>> sends = phones.stream()
                .filter(d -> dataFor.apply(d) != null)
                .map(d -> Map.entry(d.getPushToken(), dataFor.apply(d))).toList();
        if (sends.isEmpty()) return;
        Runnable send = () -> sends.forEach(e -> push.send(e.getKey(), e.getValue(), ttl));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }
}
