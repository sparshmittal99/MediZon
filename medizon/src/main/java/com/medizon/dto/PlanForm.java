package com.medizon.dto;

import com.medizon.domain.MedicationPlan;
import com.medizon.domain.ReorderMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class PlanForm {
    private Long id;

    @NotNull(message = "Choose a medicine")
    private Long medicineId;

    @Min(value = 1, message = "Dose is at least 1")
    private int dosePerIntake = 1;

    /** "HH:mm" strings from up to four time inputs; blanks are ignored. */
    private List<String> times = new ArrayList<>(List.of("08:00", "", "", ""));

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate = LocalDate.now();

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    private String instructions;

    @Min(value = 0, message = "Can't be negative")
    private int unitsRemaining;

    @NotNull
    private ReorderMode reorderMode = ReorderMode.MANUAL;

    @Min(value = 1, message = "At least 1 day")
    @Max(value = 30, message = "At most 30 days")
    private int reorderThresholdDays = 5;

    @Min(value = 1, message = "At least 1 pack")
    @Max(value = 20, message = "At most 20 packs")
    private int reorderPacks = 1;

    public static PlanForm from(MedicationPlan p) {
        PlanForm f = new PlanForm();
        f.setId(p.getId());
        f.setMedicineId(p.getMedicine().getId());
        f.setDosePerIntake(p.getDosePerIntake());
        List<String> t = new ArrayList<>();
        p.getReminderTimes().forEach(lt -> t.add(lt.toString()));
        while (t.size() < 4) t.add("");
        f.setTimes(t);
        f.setStartDate(p.getStartDate());
        f.setEndDate(p.getEndDate());
        f.setInstructions(p.getInstructions());
        f.setUnitsRemaining(p.getUnitsRemaining());
        f.setReorderMode(p.getReorderMode());
        f.setReorderThresholdDays(p.getReorderThresholdDays());
        f.setReorderPacks(p.getReorderPacks());
        return f;
    }
}
