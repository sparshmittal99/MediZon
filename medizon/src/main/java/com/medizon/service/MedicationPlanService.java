package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.DoseSlot;
import com.medizon.dto.PlanForm;
import com.medizon.repository.DoseLogRepository;
import com.medizon.repository.MedicationPlanRepository;
import com.medizon.repository.MedicineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dose schedules and the supply-at-home ledger.
 *
 * Supply model: when a dose's reminder fires the dose is assumed taken and deducted.
 * Marking it "skipped" puts the units back. So the count stays right even if the
 * customer never taps anything, and refill alerts still fire on time.
 */
@Service
@RequiredArgsConstructor
public class MedicationPlanService {

    private final MedicationPlanRepository plans;
    private final DoseLogRepository doseLogs;
    private final MedicineRepository medicines;
    private final NotificationService notifications;

    // ------------------------------------------------------------------ CRUD

    public List<MedicationPlan> forUser(User user) { return plans.findByUserOrderByActiveDescIdDesc(user); }

    public MedicationPlan get(Long id, User user) {
        return plans.findByIdAndUser(id, user).orElseThrow(() -> new BusinessException("Plan not found."));
    }

    @Transactional
    public MedicationPlan save(User user, PlanForm form) {
        MedicationPlan p = form.getId() == null ? new MedicationPlan() : get(form.getId(), user);
        if (p.getId() == null) p.setUser(user);

        Medicine m = medicines.findById(form.getMedicineId())
                .orElseThrow(() -> new BusinessException("Choose a medicine."));
        SortedSet<LocalTime> times = parseTimes(form.getTimes());
        if (times.isEmpty()) throw new BusinessException("Add at least one reminder time.");
        LocalDate start = form.getStartDate() != null ? form.getStartDate() : LocalDate.now();
        if (form.getEndDate() != null && form.getEndDate().isBefore(start)) {
            throw new BusinessException("The end date is before the start date.");
        }

        p.setMedicine(m);
        p.setDosePerIntake(form.getDosePerIntake());
        p.getReminderTimes().clear();
        p.getReminderTimes().addAll(times);
        p.setStartDate(start);
        p.setEndDate(form.getEndDate());
        p.setInstructions(form.getInstructions());
        p.setUnitsRemaining(form.getUnitsRemaining());
        p.setReorderMode(form.getReorderMode());
        p.setReorderThresholdDays(form.getReorderThresholdDays());
        p.setReorderPacks(form.getReorderPacks());
        if (!p.isRunningLow()) p.setReorderPending(false);   // edited supply re-arms alerts
        return plans.save(p);
    }

    private SortedSet<LocalTime> parseTimes(List<String> raw) {
        SortedSet<LocalTime> out = new TreeSet<>();
        if (raw == null) return out;
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            try {
                out.add(LocalTime.parse(s.trim()).withSecond(0).withNano(0));
            } catch (DateTimeParseException e) {
                throw new BusinessException("'" + s + "' isn't a valid time.");
            }
        }
        return out;
    }

    @Transactional
    public MedicationPlan toggleActive(Long id, User user) {
        MedicationPlan p = get(id, user);
        p.setActive(!p.isActive());
        return p;
    }

    @Transactional
    public void delete(Long id, User user) {
        MedicationPlan p = get(id, user);
        doseLogs.deleteByPlan(p);
        plans.delete(p);
    }

    public List<Long> activePlanIds() {
        return plans.findByActiveTrue().stream().map(MedicationPlan::getId).toList();
    }

    @Transactional
    public void closeEndedPlans(LocalDate today) {
        plans.findByActiveTrue().stream()
                .filter(p -> p.getEndDate() != null && p.getEndDate().isBefore(today))
                .forEach(p -> {
                    p.setActive(false);
                    notifications.notify(p.getUser(), NotificationType.INFO, "Course finished",
                            "Your course of " + p.getMedicine().getName() + " ended, so its reminders are off.", "/plans");
                });
    }

    // ------------------------------------------------------------------ today's doses

    @Transactional(readOnly = true)
    public List<DoseSlot> todaysDoses(User user) {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        Map<String, DoseLog> logs = doseLogs
                .findByPlanUserAndScheduledAtBetween(user, today.atStartOfDay(), today.atTime(LocalTime.MAX))
                .stream().collect(Collectors.toMap(l -> key(l.getPlan().getId(), l.getScheduledAt()), Function.identity(), (a, b) -> a));

        List<DoseSlot> slots = new ArrayList<>();
        for (MedicationPlan p : plans.findByUserAndActiveTrue(user)) {
            if (!p.isScheduledOn(today)) continue;
            for (LocalTime t : p.getReminderTimes()) {
                LocalDateTime at = today.atTime(t);
                slots.add(new DoseSlot(p, t, logs.get(key(p.getId(), at)), !at.isAfter(now)));
            }
        }
        slots.sort(Comparator.comparing(DoseSlot::getTime));
        return slots;
    }

    private static String key(Long planId, LocalDateTime at) { return planId + "@" + at; }

    /** Customer marks today's dose taken or skipped, adjusting the supply count. */
    @Transactional
    public MedicationPlan respond(User user, Long planId, LocalTime time, DoseStatus status) {
        if (status == DoseStatus.PENDING) throw new BusinessException("Choose taken or skipped.");
        MedicationPlan p = get(planId, user);
        LocalDateTime at = LocalDate.now().atTime(time);
        DoseLog log = doseLogs.findByPlanAndScheduledAt(p, at).orElseGet(() -> {
            p.setUnitsRemaining(Math.max(0, p.getUnitsRemaining() - p.getDosePerIntake()));
            return new DoseLog(p, at);
        });
        DoseStatus prev = log.getStatus();
        if (status == DoseStatus.SKIPPED && prev != DoseStatus.SKIPPED) {
            p.setUnitsRemaining(p.getUnitsRemaining() + p.getDosePerIntake());
        } else if (status == DoseStatus.TAKEN && prev == DoseStatus.SKIPPED) {
            p.setUnitsRemaining(Math.max(0, p.getUnitsRemaining() - p.getDosePerIntake()));
        }
        log.setStatus(status);
        log.setRespondedAt(LocalDateTime.now());
        doseLogs.save(log);
        return p;
    }

    // ------------------------------------------------------------------ scheduler hook

    /**
     * Sends reminders for doses due in the last 2 minutes that haven't been sent yet.
     * The look-back window covers a missed scheduler tick; the DoseLog unique key prevents duplicates.
     * Returns ids of plans whose supply changed, so refill rules can be re-checked.
     */
    @Transactional
    public List<Long> fireDueReminders(LocalDateTime now) {
        LocalDateTime windowStart = now.minusMinutes(2);
        List<Long> touched = new ArrayList<>();
        for (MedicationPlan p : plans.findByActiveTrue()) {
            if (!p.isScheduledOn(now.toLocalDate())) continue;
            for (LocalTime t : p.getReminderTimes()) {
                LocalDateTime at = now.toLocalDate().atTime(t);
                if (at.isAfter(now) || at.isBefore(windowStart)) continue;
                if (doseLogs.existsByPlanAndScheduledAt(p, at)) continue;

                doseLogs.save(new DoseLog(p, at));
                int before = p.getUnitsRemaining();
                p.setUnitsRemaining(Math.max(0, before - p.getDosePerIntake()));

                String msg = "Take " + p.getDoseLabel() + " of " + p.getMedicine().getName()
                        + (p.getInstructions() != null && !p.getInstructions().isBlank() ? " (" + p.getInstructions() + ")" : "")
                        + ". Tap to mark it taken or skipped.";
                notifications.notify(p.getUser(), NotificationType.DOSE_REMINDER,
                        "Time for " + p.getMedicine().getName(), msg, "/plans");

                if (before > 0 && p.getUnitsRemaining() == 0) {
                    notifications.notify(p.getUser(), NotificationType.REORDER_ALERT,
                            "Out of " + p.getMedicine().getName(),
                            "By our count you've used your last " + p.getMedicine().getName()
                                    + ". Refill now, or update your count if you still have some.", "/plans");
                }
                touched.add(p.getId());
            }
        }
        return touched;
    }

    // ------------------------------------------------------------------ order delivered

    /** Adds delivered packs to the matching plan's supply and re-arms its refill alert. */
    @Transactional
    public void onOrderDelivered(Order order) {
        User user = order.getUser();
        for (OrderItem item : order.getItems()) {
            Medicine m = item.getMedicine();
            int units = item.getQuantity() * m.getUnitsPerPack();
            List<MedicationPlan> matches = plans.findByUserAndMedicineAndActiveTrue(user, m);
            if (matches.isEmpty()) {
                notifications.notify(user, NotificationType.INFO, "Set reminders for " + m.getName(),
                        "Your " + m.getName() + " was delivered. Add a schedule and we'll remind you of each dose "
                                + "and when it's time to refill.", "/plans/new?medicineId=" + m.getId());
                continue;
            }
            MedicationPlan p = matches.get(0);
            p.setUnitsRemaining(p.getUnitsRemaining() + units);
            p.setReorderPending(false);
        }
    }
}
