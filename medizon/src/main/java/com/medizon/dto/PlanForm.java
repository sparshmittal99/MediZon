package com.medizon.dto;

import com.medizon.domain.MedicationPlan;
import com.medizon.domain.ReorderMode;
import com.medizon.domain.ScheduleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

@Data
public class PlanForm {
    private Long id;

    @NotNull(message = "Choose who takes it")
    private Long memberId;

    @NotNull(message = "Choose a medicine")
    private Long medicineId;

    @Min(value = 1, message = "Dose is at least 1")
    private int dosePerIntake = 1;

    /** "HH:mm" strings from up to four time inputs; blanks are ignored. */
    private List<String> times = new ArrayList<>(List.of("08:00", "", "", ""));

    @NotNull
    private ScheduleType scheduleType = ScheduleType.EVERY_DAY;

    /** For {@link ScheduleType#DAYS_OF_WEEK}. */
    private List<DayOfWeek> daysOfWeek = new ArrayList<>();

    /** "yyyy-MM-dd" strings for {@link ScheduleType#CUSTOM_DATES}; blanks are ignored. */
    private List<String> customDates = new ArrayList<>();

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate = LocalDate.now();

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    private String instructions;

    @Min(value = 0, message = "Can't be negative")
    private int unitsRemaining;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expiryDate;

    /** Move what the course needs from the common (OTC) shelf into this person's supply. */
    private boolean takeFromCommon = true;

    @NotNull
    private ReorderMode reorderMode = ReorderMode.MANUAL;

    @Min(value = 1, message = "At least 1 day")
    @Max(value = 30, message = "At most 30 days")
    private int reorderThresholdDays = 5;

    @Min(value = 1, message = "At least 1 pack")
    @Max(value = 20, message = "At most 20 packs")
    private int reorderPacks = 1;

    /** Saved address automatic refills go to; null means the default address. */
    private Long refillAddressId;

    /** Read-only on the form: this plan was created by a delivery and still needs its schedule. */
    private boolean setupRequired;

    /** The custom dates that parse, sorted, for redisplaying the form. */
    public List<LocalDate> getParsedDates() {
        TreeSet<LocalDate> out = new TreeSet<>();
        if (customDates != null) {
            for (String s : customDates) {
                if (s == null) continue;
                for (String part : s.split(",")) {
                    try {
                        out.add(LocalDate.parse(part.trim()));
                    } catch (DateTimeParseException ignored) {
                        // blank or mistyped; the save reports it
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    public static PlanForm from(MedicationPlan p) {
        PlanForm f = new PlanForm();
        f.setId(p.getId());
        f.setMemberId(p.getMember() == null ? null : p.getMember().getId());
        f.setMedicineId(p.getMedicine().getId());
        f.setDosePerIntake(p.getDosePerIntake());
        List<String> t = new ArrayList<>();
        p.getReminderTimes().forEach(lt -> t.add(lt.toString()));
        if (p.isSetupRequired() && t.isEmpty()) t.add("08:00");
        while (t.size() < 4) t.add("");
        f.setTimes(t);
        f.setScheduleType(p.getScheduleType());
        f.setDaysOfWeek(new ArrayList<>(p.getDaysOfWeek()));
        f.setCustomDates(new ArrayList<>(p.getCustomDates().stream().map(LocalDate::toString).toList()));
        f.setStartDate(p.isSetupRequired() ? LocalDate.now() : p.getStartDate());
        f.setEndDate(p.getEndDate());
        f.setInstructions(p.getInstructions());
        f.setUnitsRemaining(p.getUnitsRemaining());
        f.setExpiryDate(p.getExpiryDate());
        f.setTakeFromCommon(p.isSetupRequired());   // editing an existing plan doesn't pull from the shelf unless asked
        f.setReorderMode(p.getReorderMode());
        f.setReorderThresholdDays(p.getReorderThresholdDays());
        f.setReorderPacks(p.getReorderPacks());
        f.setRefillAddressId(p.getRefillAddress() == null ? null : p.getRefillAddress().getId());
        f.setSetupRequired(p.isSetupRequired());
        return f;
    }
}
