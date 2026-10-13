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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dose schedules and the supply-at-home ledger, per family member.
 *
 * Supply model: when a dose's reminder fires the dose is assumed taken and deducted.
 * Marking it "skipped" puts the units back. So the count stays right even if the
 * customer never taps anything, and refill alerts still fire on time.
 */
@Service
@RequiredArgsConstructor
public class MedicationPlanService {

    private static final int MAX_CUSTOM_DATES = 366;

    private final MedicationPlanRepository plans;
    private final DoseLogRepository doseLogs;
    private final MedicineRepository medicines;
    private final NotificationService notifications;
    private final MemberService memberService;
    private final AddressService addressService;

    // ------------------------------------------------------------------ CRUD

    public List<MedicationPlan> forUser(User user) { return plans.findByUserOrderByActiveDescIdDesc(user); }

    public MedicationPlan get(Long id, User user) {
        return plans.findByIdAndUser(id, user).orElseThrow(() -> new BusinessException("Plan not found."));
    }

    /** A saved plan and how many units were moved into it from the common shelf. */
    public record Saved(MedicationPlan plan, int movedFromCommon) {}

    @Transactional
    public MedicationPlan save(User user, PlanForm form) { return saveReporting(user, form).plan(); }

    @Transactional
    public Saved saveReporting(User user, PlanForm form) {
        MedicationPlan p = form.getId() == null ? new MedicationPlan() : get(form.getId(), user);
        if (p.getId() == null) p.setUser(user);

        Member member = memberService.get(form.getMemberId(), user);
        if (member.isOthers()) throw new BusinessException("Choose a family member or the common shelf.");
        Medicine m = medicines.findById(form.getMedicineId())
                .orElseThrow(() -> new BusinessException("Choose a medicine."));
        boolean duplicate = plans.findByMemberAndMedicine(member, m).stream().anyMatch(o -> !o.getId().equals(p.getId()));
        if (duplicate) {
            throw new BusinessException(member.isSelf()
                    ? "You already have " + m.getName() + " in your list. Edit that one instead."
                    : m.getName() + " is already in " + member.getLabel() + "'s list. Edit that one instead.");
        }

        if (member.isCommon()) return new Saved(saveShelfItem(p, member, m, form), 0);

        SortedSet<LocalTime> times = parseTimes(form.getTimes());
        if (times.isEmpty()) throw new BusinessException("Add at least one reminder time.");

        ScheduleType type = form.getScheduleType() == null ? ScheduleType.EVERY_DAY : form.getScheduleType();
        SortedSet<LocalDate> dates = parseDates(form.getCustomDates());
        LocalDate start = form.getStartDate() != null ? form.getStartDate() : LocalDate.now();
        switch (type) {
            case DAYS_OF_WEEK -> {
                if (form.getDaysOfWeek() == null || form.getDaysOfWeek().isEmpty()) {
                    throw new BusinessException("Pick the days of the week it's taken on.");
                }
            }
            case CUSTOM_DATES -> {
                if (dates.isEmpty()) throw new BusinessException("Add the dates it's taken on.");
                if (dates.size() > MAX_CUSTOM_DATES) throw new BusinessException("Add at most " + MAX_CUSTOM_DATES + " dates.");
            }
            case EVERY_DAY -> { }
        }
        if (type != ScheduleType.CUSTOM_DATES && form.getEndDate() != null && form.getEndDate().isBefore(start)) {
            throw new BusinessException("The end date is before the start date.");
        }

        p.setMember(member);
        p.setMedicine(m);
        p.setDosePerIntake(form.getDosePerIntake());
        p.getReminderTimes().clear();
        p.getReminderTimes().addAll(times);
        p.setScheduleType(type);
        p.getDaysOfWeek().clear();
        if (type == ScheduleType.DAYS_OF_WEEK) p.getDaysOfWeek().addAll(form.getDaysOfWeek());
        p.getCustomDates().clear();
        if (type == ScheduleType.CUSTOM_DATES) {
            p.getCustomDates().addAll(dates);
            p.setStartDate(dates.first());
            p.setEndDate(dates.last());
        } else {
            p.setStartDate(start);
            p.setEndDate(form.getEndDate());
        }
        p.setInstructions(form.getInstructions());
        p.setUnitsRemaining(form.getUnitsRemaining());
        p.setExpiryDate(form.getExpiryDate());
        p.setReorderMode(form.getReorderMode());
        p.setReorderThresholdDays(form.getReorderThresholdDays());
        p.setReorderPacks(form.getReorderPacks());
        p.setRefillAddress(form.getReorderMode() == ReorderMode.AUTO
                ? addressService.find(form.getRefillAddressId(), user).orElse(null) : null);
        if (p.isSetupRequired()) {          // first schedule for a delivered medicine switches it on
            p.setSetupRequired(false);
            p.setActive(true);
        }
        int moved = form.isTakeFromCommon() ? moveFromShelf(user, p) : 0;
        if (!p.isRunningLow()) p.setReorderPending(false);   // edited supply re-arms alerts
        return new Saved(plans.save(p), moved);
    }

    /** The common shelf only tracks what's at home: no reminders, schedule or refill alerts. */
    private MedicationPlan saveShelfItem(MedicationPlan p, Member shelf, Medicine m, PlanForm form) {
        p.setMember(shelf);
        p.setMedicine(m);
        p.getReminderTimes().clear();
        p.getDaysOfWeek().clear();
        p.getCustomDates().clear();
        p.setScheduleType(ScheduleType.EVERY_DAY);
        p.setStartDate(LocalDate.now());
        p.setEndDate(null);
        p.setInstructions(form.getInstructions());
        p.setUnitsRemaining(form.getUnitsRemaining());
        p.setExpiryDate(form.getExpiryDate());
        p.setReorderMode(ReorderMode.OFF);
        p.setReorderPending(false);
        p.setSetupRequired(false);
        p.setActive(true);
        return plans.save(p);
    }

    /**
     * Moves units of the same medicine from the common shelf into a person's plan: what the rest of the course
     * needs beyond what they already have, or everything on the shelf for an ongoing medicine. An emptied shelf
     * entry is removed, since the shelf only lists what's at home.
     */
    private int moveFromShelf(User user, MedicationPlan p) {
        Optional<MedicationPlan> shelf = planFor(memberService.common(user), p.getMedicine());
        if (shelf.isEmpty() || shelf.get().getUnitsRemaining() <= 0) return 0;
        MedicationPlan s = shelf.get();
        Integer course = p.getUnitsForRestOfCourse();
        int wanted = course == null ? s.getUnitsRemaining() : Math.max(0, course - p.getUnitsRemaining());
        int moved = Math.min(wanted, s.getUnitsRemaining());
        if (moved == 0) return 0;
        p.addSupply(moved, s.getExpiryDate());
        s.setUnitsRemaining(s.getUnitsRemaining() - moved);
        if (s.getUnitsRemaining() == 0) {
            doseLogs.deleteByPlan(s);
            plans.delete(s);
        }
        return moved;
    }

    /** Units of each medicine (by id) on the account's common shelf, for the plan form. */
    @Transactional
    public Map<Long, Integer> shelfStock(User user) {
        Member shelf = memberService.common(user);
        Map<Long, Integer> out = new HashMap<>();
        plans.findByMember(shelf).forEach(p -> out.merge(p.getMedicine().getId(), p.getUnitsRemaining(), Integer::sum));
        return out;
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

    private SortedSet<LocalDate> parseDates(List<String> raw) {
        SortedSet<LocalDate> out = new TreeSet<>();
        if (raw == null) return out;
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            for (String part : s.split(",")) {     // the form may post one comma-separated value
                if (part.isBlank()) continue;
                try {
                    out.add(LocalDate.parse(part.trim()));
                } catch (DateTimeParseException e) {
                    throw new BusinessException("'" + part.trim() + "' isn't a valid date.");
                }
            }
        }
        return out;
    }

    @Transactional
    public MedicationPlan toggleActive(Long id, User user) {
        MedicationPlan p = get(id, user);
        if (p.isSetupRequired()) throw new BusinessException("Set the dose times for " + p.getMedicine().getName() + " first.");
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
                .filter(p -> p.isFinished(today))
                .forEach(p -> {
                    p.setActive(false);
                    notifications.notify(p.getUser(), NotificationType.INFO, "Course finished",
                            "The course of " + p.getTitle() + " ended, so its reminders are off.", "/plans", p.getMember());
                });
    }

    // ------------------------------------------------------------------ expiry

    /**
     * Takes expired stock out of My medicines and tells the customer not to use it. Entries that are only stock
     * (the common shelf, and delivered medicines never given a schedule) are removed. A person's plan keeps its
     * schedule and reminders, but its expired units are written off, so the usual refill alert or auto order follows.
     * Returns ids of the plans kept, so refill rules can be re-checked.
     */
    @Transactional
    public List<Long> clearExpired(LocalDate today) {
        List<Long> kept = new ArrayList<>();
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        for (MedicationPlan p : plans.findByExpiryDateBefore(today)) {
            String expired = p.getExpiryDate().format(day);
            String what = p.getUnitsRemaining() + " " + p.getMedicine().getUnitLabel() + " of " + p.getTitle();
            boolean stockOnly = p.isOnCommonShelf() || p.isSetupRequired();
            String message = p.getUnitsRemaining() == 0
                    ? p.getTitle() + " passed its expiry date on " + expired + "."
                    : what + " expired on " + expired + ". Don't use them; dispose of them safely (many pharmacies take back expired medicines).";
            if (stockOnly) {
                notifications.notify(p.getUser(), NotificationType.EXPIRY_ALERT, "Expired: " + p.getTitle(),
                        message + " We've removed it from your medicines.", planLink(p), p.getMember());
                doseLogs.deleteByPlan(p);
                plans.delete(p);
                continue;
            }
            if (p.getUnitsRemaining() > 0) {       // nothing at home means nothing to throw away
                notifications.notify(p.getUser(), NotificationType.EXPIRY_ALERT, "Expired: " + p.getTitle(),
                        message + " We've taken them off the supply count"
                                + (p.isActive() ? ", so refill to keep taking it on schedule." : "."), planLink(p), p.getMember());
            }
            p.setUnitsRemaining(0);
            p.setExpiryDate(null);
            kept.add(p.getId());
        }
        return kept;
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

                Member who = p.getMember();
                String forWhom = who == null || who.isSelf() || who.isCommon() ? "" : who.getName() + ": ";
                String msg = forWhom + "take " + p.getDoseLabel() + " of " + p.getMedicine().getName()
                        + (p.getInstructions() != null && !p.getInstructions().isBlank() ? " (" + p.getInstructions() + ")" : "")
                        + ". Tap to mark it taken or skipped.";
                notifications.notify(p.getUser(), NotificationType.DOSE_REMINDER,
                        "Time for " + p.getTitle(), capitalize(msg), planLink(p), who);

                if (before > 0 && p.getUnitsRemaining() == 0) {
                    notifications.notify(p.getUser(), NotificationType.REORDER_ALERT,
                            "Out of " + p.getTitle(),
                            "By our count the last of " + p.getTitle() + " has been used. "
                                    + "Refill now, or update the count if there's still some left.", planLink(p), who);
                }
                touched.add(p.getId());
            }
        }
        return touched;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String planLink(MedicationPlan p) {
        return p.getMember() == null ? "/plans" : "/plans?member=" + p.getMember().getId();
    }

    // ------------------------------------------------------------------ order delivered / returned

    /**
     * Adds delivered packs to the plans of the members each line was ordered for, splitting the units evenly when
     * a line was for several people. A member who has no plan for that medicine yet gets one that holds the
     * supply and waits for its first schedule (dose times and days).
     */
    @Transactional
    public void onOrderDelivered(Order order) {
        User user = order.getUser();
        Map<Long, List<String>> needSetup = new LinkedHashMap<>();
        Map<Long, Member> who = new HashMap<>();
        List<String> shelved = new ArrayList<>();

        for (OrderItem item : order.getItems()) {
            Medicine m = item.getMedicine();
            List<Member> targets = targets(item, user);
            int[] shares = item.shareOf(targets, item.getQuantity() * m.getUnitsPerPack());
            for (int i = 0; i < targets.size(); i++) {
                Member member = targets.get(i);
                if (member.isOthers()) continue;   // bought for someone outside the household: nothing to track
                Optional<MedicationPlan> existing = planFor(member, m);
                if (existing.isPresent()) {
                    MedicationPlan p = existing.get();
                    p.addSupply(shares[i], item.getExpiryDate());
                    p.setReorderPending(false);
                    continue;
                }
                MedicationPlan p = new MedicationPlan();
                p.setUser(user);
                p.setMember(member);
                p.setMedicine(m);
                p.addSupply(shares[i], item.getExpiryDate());
                p.setReorderPacks(Math.max(1, shares[i] / Math.max(1, m.getUnitsPerPack())));
                if (member.isCommon()) {
                    // Common medicines go straight onto the shelf; there's nothing to set up
                    p.setReorderMode(ReorderMode.OFF);
                    plans.save(p);
                    shelved.add(m.getName());
                    continue;
                }
                p.setActive(false);
                p.setSetupRequired(true);
                plans.save(p);
                needSetup.computeIfAbsent(member.getId(), k -> new ArrayList<>()).add(m.getName());
                who.put(member.getId(), member);
            }
        }

        if (!shelved.isEmpty()) {
            Member shelf = memberService.common(user);
            notifications.notify(user, NotificationType.INFO, "Added to common medicines",
                    String.join(", ", shelved) + (shelved.size() == 1 ? " is" : " are") + " now on the common medicines shelf at home. "
                            + "If someone needs to take one regularly, add it to their list and we'll move the doses across.",
                    "/plans?member=" + shelf.getId(), shelf);
        }

        needSetup.forEach((memberId, names) -> {
            Member member = who.get(memberId);
            String list = String.join(", ", names);
            String whose = member.isSelf() ? "your" : member.getName() + "'s";
            notifications.notify(user, NotificationType.INFO,
                    "Set reminders" + (member.isSelf() ? "" : " for " + member.getLabel()),
                    list + (names.size() == 1 ? " was" : " were") + " delivered and added to " + whose + " list. "
                            + "Set the dose times and days so we can send reminders and refill before it runs out.",
                    "/plans?member=" + memberId, member);
        });
    }

    /** Takes returned packs back out of the supply of the members they were delivered to. */
    @Transactional
    public void onItemsReturned(OrderItem item, int packs) {
        Medicine m = item.getMedicine();
        List<Member> targets = targets(item, item.getOrder().getUser());
        int[] shares = item.shareOf(targets, packs * m.getUnitsPerPack());
        for (int i = 0; i < targets.size(); i++) {
            int share = shares[i];
            if (targets.get(i).isOthers()) continue;
            planFor(targets.get(i), m).ifPresent(p -> p.setUnitsRemaining(Math.max(0, p.getUnitsRemaining() - share)));
        }
    }

    /** Who an order line went to: its members still on the account, else the account holder. */
    private List<Member> targets(OrderItem item, User user) {
        List<Member> targets = item.getSortedMembers().stream().filter(x -> !x.isRemoved()).toList();
        return targets.isEmpty() ? List.of(memberService.self(user)) : targets;
    }

    /** The member's plan for a medicine, preferring an active one. */
    private Optional<MedicationPlan> planFor(Member member, Medicine m) {
        return plans.findByMemberAndMedicine(member, m).stream()
                .min(Comparator.comparing((MedicationPlan p) -> !p.isActive()).thenComparing(MedicationPlan::getId));
    }

}
