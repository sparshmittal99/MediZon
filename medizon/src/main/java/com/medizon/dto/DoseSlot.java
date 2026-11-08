package com.medizon.dto;

import com.medizon.domain.DoseLog;
import com.medizon.domain.MedicationPlan;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalTime;

/** One cell of today's dose strip. */
@Getter
@AllArgsConstructor
public class DoseSlot {
    private final MedicationPlan plan;
    private final LocalTime time;
    private final DoseLog log;
    private final boolean past;

    /** taken | skipped | pending (reminder sent, no answer) | due (time passed) | upcoming */
    public String getState() {
        if (log != null) return log.getStatus().name().toLowerCase();
        return past ? "due" : "upcoming";
    }

    /** Already answered: offers the opposite answer, to fix a wrong tap. */
    public boolean isChangeable() {
        String s = getState();
        return s.equals("taken") || s.equals("skipped");
    }

    public boolean isAnswerable() {
        String s = getState();
        return s.equals("pending") || s.equals("due");
    }
}
