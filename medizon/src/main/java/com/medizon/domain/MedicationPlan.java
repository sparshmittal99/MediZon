package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SortNatural;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One medicine a customer takes regularly. Holds three things together:
 * the reminder schedule, the supply left at home, and the refill preference.
 */
@Entity
@Getter @Setter @NoArgsConstructor
public class MedicationPlan {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @ManyToOne(optional = false)
    private Medicine medicine;

    /** Units (tablets / ml ...) per intake. */
    private int dosePerIntake = 1;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_reminder_times", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "reminder_time")
    @SortNatural
    private SortedSet<LocalTime> reminderTimes = new TreeSet<>();

    private LocalDate startDate = LocalDate.now();
    private LocalDate endDate;

    @Column(length = 300)
    private String instructions;

    private boolean active = true;

    // ---- supply at home ----
    private int unitsRemaining;

    // ---- refill preference ----
    @Enumerated(EnumType.STRING)
    private ReorderMode reorderMode = ReorderMode.MANUAL;

    /** Alert / auto-order when supply covers this many days or fewer. */
    private int reorderThresholdDays = 5;

    /** Packs to order on refill. */
    private int reorderPacks = 1;

    /** True once an alert was sent or an auto-order placed; cleared when new stock arrives. */
    private boolean reorderPending;

    private LocalDateTime lastReorderAt;

    private LocalDateTime createdAt = LocalDateTime.now();

    public int getDailyUnits() { return dosePerIntake * reminderTimes.size(); }

    public Double getDaysLeft() {
        int daily = getDailyUnits();
        return daily == 0 ? null : (double) unitsRemaining / daily;
    }

    public int getDaysLeftRounded() {
        Double d = getDaysLeft();
        return d == null ? 0 : (int) Math.floor(d);
    }

    public LocalDate getRunOutDate() {
        return LocalDate.now().plusDays(getDaysLeftRounded());
    }

    public boolean isRunningLow() {
        Double d = getDaysLeft();
        return d != null && d <= reorderThresholdDays;
    }

    /** 0-100, relative to a 30 day supply; drives the supply meter. */
    public int getSupplyPercent() {
        Double d = getDaysLeft();
        if (d == null) return 0;
        return (int) Math.min(100, Math.round(d / 30.0 * 100));
    }

    public boolean isScheduledOn(LocalDate day) {
        return active && !day.isBefore(startDate) && (endDate == null || !day.isAfter(endDate));
    }

    public String getDoseLabel() {
        return dosePerIntake + " " + medicine.getUnitLabel();
    }
}
