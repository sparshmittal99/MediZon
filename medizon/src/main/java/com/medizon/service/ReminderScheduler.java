package com.medizon.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final MedicationPlanService planService;
    private final ReorderService reorderService;
    private final ReminderService reminderService;

    /**
     * Every 10 seconds: send due dose reminders, re-check refill rules for those plans, and send assistant reminders.
     * Running often (instead of once on the minute) means a reminder goes out within seconds of its time, even when
     * the timer wakes a few milliseconds early. Dose logs stop the same dose from being sent twice.
     */
    @Scheduled(cron = "*/10 * * * * *")
    public void doseReminders() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        try {
            planService.fireDueReminders(now).stream().distinct().forEach(this::safeEvaluate);
        } catch (Exception e) {
            log.error("Dose reminder run failed", e);
        }
        try {
            reminderService.fireDue(now);
        } catch (Exception e) {
            log.error("Assistant reminder run failed", e);
        }
    }

    /** Every morning: clear expired stock, close finished courses and sweep all plans for low supply. */
    @Scheduled(cron = "0 0 9 * * *")
    public void dailySupplyCheck() {
        clearExpiredStock();
        planService.closeEndedPlans(LocalDate.now());
        planService.activePlanIds().forEach(this::safeEvaluate);
    }

    /** Also on startup, so expiries that passed while the app was down are handled straight away. */
    @EventListener(ApplicationReadyEvent.class)
    public void clearExpiredStock() {
        try {
            planService.clearExpired(LocalDate.now()).forEach(this::safeEvaluate);   // written-off supply may need a refill
        } catch (Exception e) {
            log.error("Expired stock sweep failed", e);
        }
    }

    private void safeEvaluate(Long planId) {
        try {
            reorderService.evaluate(planId);
        } catch (Exception e) {
            log.error("Refill check failed for plan {}", planId, e);
        }
    }
}
