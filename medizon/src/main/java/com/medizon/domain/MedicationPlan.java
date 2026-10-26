package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SortNatural;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * One medicine a family member takes regularly. Holds three things together:
 * the reminder schedule, the supply left at home, and the refill preference.
 */
@Entity
@Getter @Setter @NoArgsConstructor
public class MedicationPlan {

    /** How far ahead the supply forecast looks; supply lasting longer than this counts as "enough". */
    private static final int FORECAST_DAYS = 366;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    /** Who takes it. Only null on plans created before family members existed; those are moved to the account holder at startup. */
    @ManyToOne
    private Member member;

    @ManyToOne(optional = false)
    private Medicine medicine;

    /** Units (tablets / ml ...) per intake. */
    private int dosePerIntake = 1;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_reminder_times", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "reminder_time")
    @SortNatural
    private SortedSet<LocalTime> reminderTimes = new TreeSet<>();

    /** Null on plans created before schedules had a type; read through {@link #getScheduleType()}. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ScheduleType scheduleType = ScheduleType.EVERY_DAY;

    /** Dose days for {@link ScheduleType#DAYS_OF_WEEK}. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_days", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "day_of_week", length = 10)
    @Enumerated(EnumType.STRING)
    @SortNatural
    private SortedSet<DayOfWeek> daysOfWeek = new TreeSet<>();

    /** Dose days for {@link ScheduleType#CUSTOM_DATES}. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_dates", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "dose_date")
    @SortNatural
    private SortedSet<LocalDate> customDates = new TreeSet<>();

    /** Start and end apply to every-day and weekly schedules; custom dates carry their own days. */
    private LocalDate startDate = LocalDate.now();
    private LocalDate endDate;

    @Column(length = 300)
    private String instructions;

    private boolean active = true;

    /**
     * Created automatically when a delivery arrived for a member with no plan for that medicine yet. It holds the
     * delivered supply but stays off until the customer sets dose times and days.
     */
    @Column(columnDefinition = "boolean not null default false")
    private boolean setupRequired;

    // ---- supply at home ----
    private int unitsRemaining;

    /** Expiry of the supply at home: set from deliveries, or entered by the customer. */
    private LocalDate expiryDate;

    // ---- refill preference ----
    @Enumerated(EnumType.STRING)
    private ReorderMode reorderMode = ReorderMode.MANUAL;

    /** Alert / auto-order when supply covers this many days or fewer. */
    private int reorderThresholdDays = 5;

    /** Packs to order on refill. */
    private int reorderPacks = 1;

    /** Where automatic refills are delivered; empty means the default address. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Address refillAddress;

    /** True once an alert was sent or an auto-order placed; cleared when new stock arrives. */
    private boolean reorderPending;

    private LocalDateTime lastReorderAt;

    /** Short reason the automatic refill can't be placed yet; empty once it's ordered. */
    private String reorderIssue;

    /** The order that refills this low-supply cycle: the automatic one, or one the customer placed after the alert. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Order refillOrder;

    /** Re-arms alerts and automatic refills for the next time supply runs low. */
    public void clearRefill() {
        reorderPending = false;
        reorderIssue = null;
        refillOrder = null;
    }

    /** The refill line on the plan's card: short text, and "muted", "warning" or "danger" for its colour. */
    public record RefillStatus(String text, String tone) {}

    public RefillStatus getRefillStatus() {
        if (!reorderPending || reorderMode == ReorderMode.OFF) return null;
        if (refillOrder != null) return switch (refillOrder.getStatus()) {
            case PLACED, CONFIRMED -> new RefillStatus("Refill ordered on "
                    + refillOrder.getCreatedAt().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)), "muted");
            case SHIPPED -> new RefillStatus("Refill on the way", "muted");
            case DELIVERED -> new RefillStatus("Refill delivered", "muted");
            case CANCELLED -> new RefillStatus("Refill cancelled. Reorder when you're ready", "warning");
        };
        if (reorderMode == ReorderMode.MANUAL) return new RefillStatus("Running low. Reorder soon", "warning");
        return new RefillStatus(reorderIssue != null ? reorderIssue : "Refill pending", reorderIssue != null ? "danger" : "muted");
    }

    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isExpired() { return expiryDate != null && expiryDate.isBefore(LocalDate.now()); }

    /** Expires within 30 days. */
    public boolean isExpiringSoon() {
        return expiryDate != null && !isExpired() && expiryDate.isBefore(LocalDate.now().plusDays(30));
    }

    /** On the household's common shelf: stock at home only, no reminders. */
    public boolean isOnCommonShelf() { return member != null && member.isCommon(); }

    /**
     * Units the rest of the course needs from today, following the schedule; null when it has no end date
     * (ongoing medicines) or no reminder times.
     */
    public Integer getUnitsForRestOfCourse() {
        int perDay = getDailyUnits();
        if (perDay == 0) return null;
        LocalDate today = LocalDate.now();
        LocalDate last = getScheduleType() == ScheduleType.CUSTOM_DATES
                ? (customDates.isEmpty() ? today.minusDays(1) : customDates.last()) : endDate;
        if (last == null) return null;
        int total = 0;
        for (LocalDate d = today; !d.isAfter(last); d = d.plusDays(1)) {
            if (isDoseDay(d)) total += perDay;
        }
        return total;
    }

    /**
     * Adds units to the supply. The expiry shown is the earliest one still at home, since older stock gets used first;
     * when the supply was used up, the new stock's expiry replaces it.
     */
    public void addSupply(int units, LocalDate expiry) {
        if (expiry != null) {
            if (unitsRemaining <= 0 || expiryDate == null || isExpired() || expiry.isBefore(expiryDate)) expiryDate = expiry;
        }
        unitsRemaining += units;
    }

    public ScheduleType getScheduleType() { return scheduleType == null ? ScheduleType.EVERY_DAY : scheduleType; }

    /** Units taken on a dose day. */
    public int getDailyUnits() { return dosePerIntake * reminderTimes.size(); }

    /** Is this a dose day by the schedule alone (ignoring pause)? */
    public boolean isDoseDay(LocalDate day) {
        return switch (getScheduleType()) {
            case CUSTOM_DATES -> customDates.contains(day);
            case DAYS_OF_WEEK -> inDateRange(day) && daysOfWeek.contains(day.getDayOfWeek());
            case EVERY_DAY -> inDateRange(day);
        };
    }

    private boolean inDateRange(LocalDate day) {
        return (startDate == null || !day.isBefore(startDate)) && (endDate == null || !day.isAfter(endDate));
    }

    public boolean isScheduledOn(LocalDate day) { return active && isDoseDay(day); }

    /** The schedule has no dose days left. */
    public boolean isFinished(LocalDate today) {
        if (getScheduleType() == ScheduleType.CUSTOM_DATES) {
            return customDates.isEmpty() || customDates.last().isBefore(today);
        }
        return endDate != null && endDate.isBefore(today);
    }

    /**
     * Whole dose days the supply at home covers from today, walking the actual schedule (so weekly and custom-date
     * plans aren't counted as daily). Null when there are no reminder times, or when the supply outlasts the schedule.
     */
    public Double getDaysLeft() {
        int perDay = getDailyUnits();
        if (perDay == 0) return null;
        int units = unitsRemaining;
        LocalDate day = LocalDate.now();
        for (int i = 0; i < FORECAST_DAYS; i++, day = day.plusDays(1)) {
            if (!isDoseDay(day)) continue;
            if (units < perDay) return (double) i;
            units -= perDay;
        }
        return null;
    }

    public int getDaysLeftRounded() {
        Double d = getDaysLeft();
        return d == null ? 0 : (int) Math.floor(d);
    }

    /** Supply lasts until the schedule ends (or for over a year). */
    public boolean isSupplyEnough() { return getDailyUnits() > 0 && getDaysLeft() == null; }

    public LocalDate getRunOutDate() {
        return LocalDate.now().plusDays(getDaysLeftRounded());
    }

    public boolean isRunningLow() {
        Double d = getDaysLeft();
        return d != null && d <= reorderThresholdDays;
    }

    /** 0-100, relative to a 30 day supply; drives the supply meter. */
    public int getSupplyPercent() {
        if (isSupplyEnough()) return 100;
        Double d = getDaysLeft();
        if (d == null) return 0;
        return (int) Math.min(100, Math.round(d / 30.0 * 100));
    }

    public String getDoseLabel() {
        return dosePerIntake + " " + medicine.getUnitLabel();
    }

    /** "Every day", "Mon, Wed, Fri" or "12 Oct, 19 Oct and 3 more". */
    public String getScheduleLabel() {
        return switch (getScheduleType()) {
            case EVERY_DAY -> "Every day";
            case DAYS_OF_WEEK -> daysOfWeek.isEmpty() ? "No days chosen" : daysOfWeek.stream()
                    .map(d -> d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).collect(Collectors.joining(", "));
            case CUSTOM_DATES -> {
                DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
                SortedSet<LocalDate> upcoming = customDates.tailSet(LocalDate.now());
                if (upcoming.isEmpty()) yield "No dates left";
                String first = upcoming.stream().limit(2).map(f::format).collect(Collectors.joining(", "));
                yield upcoming.size() > 2 ? first + " and " + (upcoming.size() - 2) + " more" : first;
            }
        };
    }

    /** Medicine name plus whose it is, for alerts: "Metformin (Asha)". */
    public String getTitle() {
        return medicine.getName() + (member == null ? "" : member.getSuffix());
    }
}
