package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.DoseLogRepository;
import com.medizon.repository.MedicationPlanRepository;
import com.medizon.repository.NotificationDeviceRepository;
import com.medizon.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The Android app rings dose alarms itself, so they work with the app closed and while the server sleeps. It links
 * once from a signed-in page (a sync token tied to this device's alert settings), then fetches the next week of
 * alarms in the background and reports doses answered from the alarm screen.
 */
@Service
@RequiredArgsConstructor
public class AppScheduleService {

    /** How far ahead the app schedules alarms; it re-syncs every few hours, so the window keeps rolling. */
    static final int DAYS_AHEAD = 7;
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    /** Always with seconds and offset ("2026-11-05T08:00:00+05:30"); OffsetDateTime.toString drops ":00". */
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NotificationDeviceRepository devices;
    private final MedicationPlanRepository plans;
    private final DoseLogRepository doseLogs;
    private final ReminderRepository reminders;
    private final MedicationPlanService planService;
    private final ReorderService reorderService;
    private final MaintenanceService maintenance;

    public record Alarm(String id, String kind, Long planId, Long reminderId, String at, String title, String body, String path) {}

    /** A one-time phone notification (scheduled downtime); the app shows each id once. */
    public record Notice(String id, String title, String body, String path) {}

    public record Schedule(String generatedAt, List<Alarm> alarms, List<Notice> notices) {}

    /** A new sync token for this device; the previous one stops working. Only its hash is stored. */
    @Transactional
    public String link(NotificationDevice device) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        NotificationDevice d = devices.findById(device.getId()).orElseThrow();
        d.setAppTokenHash(hash(token));
        d.setAppLinkedAt(LocalDateTime.now());
        return token;
    }

    /** Signing out in the app: the token stops working. */
    @Transactional
    public void unlink(NotificationDevice device) {
        devices.findById(device.getId()).ifPresent(d -> {
            d.setAppTokenHash(null);
            d.setAppLinkedAt(null);
        });
    }

    @Transactional(readOnly = true)
    public Optional<NotificationDevice> device(String token) {
        if (token == null || token.isBlank() || token.length() > 100) return Optional.empty();
        return devices.findByAppTokenHash(hash(token));
    }

    /** Doses and one-off reminders from now to a week ahead, for the members this device follows. */
    @Transactional(readOnly = true)
    public Schedule schedule(NotificationDevice device, LocalDateTime now) {
        User user = device.getUser();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = now.toLocalDate();
        LocalDateTime until = today.plusDays(DAYS_AHEAD).atStartOfDay();

        Set<String> answered = new HashSet<>();
        doseLogs.findByPlanUserAndScheduledAtBetween(user, now, until).stream()
                .filter(l -> l.getStatus() != DoseStatus.PENDING)
                .forEach(l -> answered.add(l.getPlan().getId() + "@" + l.getScheduledAt()));

        List<Alarm> out = new ArrayList<>();
        for (MedicationPlan p : plans.findByUserAndActiveTrue(user)) {
            if (p.isSetupRequired() || p.isOnCommonShelf() || p.getReminderTimes().isEmpty()) continue;
            if (!device.allows(p.getMember())) continue;
            String body = "Take " + p.getDoseLabel()
                    + (p.getInstructions() != null && !p.getInstructions().isBlank() ? " · " + p.getInstructions().trim() : "");
            for (LocalDate day = today; day.isBefore(until.toLocalDate()); day = day.plusDays(1)) {
                if (!p.isScheduledOn(day)) continue;
                for (LocalTime t : p.getReminderTimes()) {
                    LocalDateTime at = day.atTime(t);
                    if (!at.isAfter(now) || answered.contains(p.getId() + "@" + at)) continue;
                    out.add(new Alarm("dose-" + p.getId() + "-" + at.format(ID_TIME), "DOSE", p.getId(), null,
                            at.atZone(zone).format(AT), p.getTitle(), body,
                            MedicationPlanService.planLink(p)));
                }
            }
        }
        for (Reminder r : reminders.findByUserAndDoneFalseOrderByDueAtAsc(user)) {
            if (r.getDueAt() == null || !r.getDueAt().isAfter(now) || !r.getDueAt().isBefore(until)) continue;
            out.add(new Alarm("reminder-" + r.getId(), "REMINDER", null, r.getId(),
                    r.getDueAt().atZone(zone).format(AT), "Reminder", r.getText(), "/notifications"));
        }
        out.sort(Comparator.comparing(Alarm::at));
        return new Schedule(now.atZone(zone).format(AT), out, notices());
    }

    private List<Notice> notices() {
        List<Notice> out = new ArrayList<>();
        for (MaintenanceWindow w : maintenance.announced()) {
            out.add(new Notice("maint-" + w.getId(), "Scheduled downtime", "MediZon will be down for maintenance "
                    + MaintenanceService.when(w) + ". The app may not work during this time.", "/"));
        }
        for (MaintenanceWindow w : maintenance.cancelledUpcoming()) {
            out.add(new Notice("maint-" + w.getId() + "-cancelled", "Downtime cancelled", "The maintenance planned "
                    + MaintenanceService.when(w) + " is cancelled. MediZon works as usual.", "/"));
        }
        return out;
    }

    /** A dose answered on the alarm screen. Only a dose this plan really has, due in the last two days. */
    @Transactional
    public void answer(NotificationDevice device, Long planId, LocalDateTime at, DoseStatus status) {
        LocalDateTime now = LocalDateTime.now();
        if (at.isAfter(now.plusMinutes(5)) || at.isBefore(now.minusDays(2))) {
            throw new BusinessException("That dose is too far from now to mark.");
        }
        User user = device.getUser();
        MedicationPlan p = planService.get(planId, user);
        if (!p.getReminderTimes().contains(at.toLocalTime()) || !p.isDoseDay(at.toLocalDate())) {
            throw new BusinessException("That isn't one of this medicine's dose times.");
        }
        planService.respondAt(user, planId, at, status);
        reorderService.evaluate(planId);
    }

    static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
