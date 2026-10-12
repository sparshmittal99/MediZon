package com.medizon.domain;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class MedicationPlanScheduleTest {

    private static MedicationPlan plan(int units, ScheduleType type) {
        Medicine m = new Medicine();
        m.setName("Vitamin D3");
        m.setDosageForm("Capsule");
        MedicationPlan p = new MedicationPlan();
        p.setMedicine(m);
        p.setDosePerIntake(1);
        p.getReminderTimes().add(LocalTime.of(9, 0));
        p.setScheduleType(type);
        p.setStartDate(LocalDate.now());
        p.setUnitsRemaining(units);
        return p;
    }

    @Test
    void everyDayMatchesUnitsDividedByDailyDose() {
        MedicationPlan p = plan(10, ScheduleType.EVERY_DAY);
        p.getReminderTimes().add(LocalTime.of(21, 0));      // 2 a day
        assertThat(p.getDaysLeft()).isEqualTo(5.0);
        assertThat(p.isRunningLow()).isTrue();               // threshold 5
    }

    @Test
    void weeklyScheduleLastsSevenTimesLonger() {
        MedicationPlan p = plan(4, ScheduleType.DAYS_OF_WEEK);
        p.getDaysOfWeek().add(LocalDate.now().getDayOfWeek());
        // 4 capsules, one a week: the 5th weekly dose is the first one not covered
        assertThat(p.getDaysLeft()).isEqualTo(28.0);
        assertThat(p.isRunningLow()).isFalse();
        assertThat(p.isDoseDay(LocalDate.now().plusDays(1))).isFalse();
    }

    @Test
    void customDatesOnlyCountTheirOwnDays() {
        MedicationPlan p = plan(2, ScheduleType.CUSTOM_DATES);
        LocalDate today = LocalDate.now();
        p.getCustomDates().add(today.plusDays(3));
        p.getCustomDates().add(today.plusDays(10));
        assertThat(p.isSupplyEnough()).isTrue();             // 2 units cover both dates
        assertThat(p.getDaysLeft()).isNull();
        assertThat(p.isScheduledOn(today.plusDays(3))).isTrue();
        assertThat(p.isScheduledOn(today.plusDays(4))).isFalse();
        assertThat(p.isFinished(today.plusDays(11))).isTrue();
        assertThat(p.isFinished(today)).isFalse();

        p.getCustomDates().add(today.plusDays(20));
        assertThat(p.getDaysLeft()).isEqualTo(20.0);         // runs out before the third date
    }

    @Test
    void supplyOutlastingAnEndDateIsEnough() {
        MedicationPlan p = plan(30, ScheduleType.EVERY_DAY);
        p.setEndDate(LocalDate.now().plusDays(4));
        assertThat(p.isSupplyEnough()).isTrue();
        assertThat(p.isRunningLow()).isFalse();
        assertThat(p.getSupplyPercent()).isEqualTo(100);
    }

    @Test
    void asNeededPlansHaveNoForecast() {
        MedicationPlan p = plan(15, ScheduleType.EVERY_DAY);
        p.getReminderTimes().clear();
        assertThat(p.getDaysLeft()).isNull();
        assertThat(p.isSupplyEnough()).isFalse();
        assertThat(p.isRunningLow()).isFalse();
    }

    @Test
    void unitsForRestOfCourseFollowTheSchedule() {
        MedicationPlan fixed = plan(0, ScheduleType.EVERY_DAY);
        fixed.getReminderTimes().add(LocalTime.of(21, 0));   // 2 a day
        fixed.setEndDate(LocalDate.now().plusDays(4));        // today + 4 more days
        assertThat(fixed.getUnitsForRestOfCourse()).isEqualTo(10);

        MedicationPlan dates = plan(0, ScheduleType.CUSTOM_DATES);
        dates.getCustomDates().add(LocalDate.now().minusDays(1));   // already past
        dates.getCustomDates().add(LocalDate.now().plusDays(2));
        dates.getCustomDates().add(LocalDate.now().plusDays(9));
        assertThat(dates.getUnitsForRestOfCourse()).isEqualTo(2);

        assertThat(plan(0, ScheduleType.EVERY_DAY).getUnitsForRestOfCourse()).isNull();   // ongoing
    }

    @Test
    void supplyKeepsTheEarliestExpiryStillAtHome() {
        LocalDate soon = LocalDate.now().plusMonths(3), later = LocalDate.now().plusMonths(12);
        MedicationPlan p = plan(0, ScheduleType.EVERY_DAY);
        p.addSupply(10, later);
        assertThat(p.getExpiryDate()).isEqualTo(later);
        p.addSupply(10, soon);
        assertThat(p.getExpiryDate()).isEqualTo(soon);
        p.addSupply(10, later);
        assertThat(p.getExpiryDate()).isEqualTo(soon);       // older stock is used first
        assertThat(p.getUnitsRemaining()).isEqualTo(30);

        p.setUnitsRemaining(0);                               // used up: new stock's expiry takes over
        p.addSupply(10, later);
        assertThat(p.getExpiryDate()).isEqualTo(later);

        p.setExpiryDate(LocalDate.now().minusDays(1));        // expired stock is replaced too
        assertThat(p.isExpired()).isTrue();
        p.addSupply(5, later);
        assertThat(p.getExpiryDate()).isEqualTo(later);
        assertThat(p.isExpiringSoon()).isFalse();
    }

    @Test
    void scheduleLabels() {
        MedicationPlan weekly = plan(4, ScheduleType.DAYS_OF_WEEK);
        weekly.getDaysOfWeek().add(DayOfWeek.FRIDAY);
        weekly.getDaysOfWeek().add(DayOfWeek.MONDAY);
        assertThat(weekly.getScheduleLabel()).isEqualTo("Mon, Fri");
        assertThat(plan(4, ScheduleType.EVERY_DAY).getScheduleLabel()).isEqualTo("Every day");
    }
}
