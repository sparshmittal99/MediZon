package com.medizon.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * What the AI read from a prescription. Only a suggestion for the customer's cart and a head start for the
 * pharmacist, who still verifies the prescription before anything is dispatched.
 *
 * @param legible false when the file isn't a readable prescription at all
 * @param prescribedOnIso the prescription date as YYYY-MM-DD; empty when there's no readable date
 * @param signed a doctor's signature or stamp is visible
 * @param concerns anything the AI thinks a pharmacist should look at (an altered date, a cut-off page...)
 */
public record PrescriptionReading(String doctorName, String registrationNumber, String patientName, String prescribedOn,
                                  String prescribedOnIso, boolean legible, boolean signed, List<String> concerns,
                                  List<Line> medicines) {

    public PrescriptionReading {
        concerns = concerns == null ? List.of() : concerns.stream().filter(c -> c != null && !c.isBlank()).toList();
        medicines = medicines == null ? List.of() : medicines;
    }

    /**
     * One medicine as written.
     *
     * @param catalogId the matching catalogue product, or null when we don't sell it (checked against the catalogue)
     * @param unitsNeeded tablets, capsules... for the whole course, when the dose and duration are written
     * @param confidence high, medium or low: how sure the reading of this line is
     */
    public record Line(String writtenAs, String genericName, String strength, String dosage, String duration,
                       Integer unitsNeeded, Long catalogId, String confidence) {

        @JsonIgnore
        public boolean isUnsure() { return !"high".equalsIgnoreCase(confidence); }

        /** "Dolo 650, 1-0-1 for 5 days" */
        @JsonIgnore
        public String getSummary() {
            StringBuilder s = new StringBuilder(writtenAs == null ? "?" : writtenAs);
            if (dosage != null && !dosage.isBlank()) s.append(", ").append(dosage);
            if (duration != null && !duration.isBlank()) s.append(" for ").append(duration);
            return s.toString();
        }
    }
}
