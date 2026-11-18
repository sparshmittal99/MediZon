package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One change the customer made to an uploaded prescription's details before the pharmacist reviewed it: which field,
 * what the AI read there, what it said before the change and what the customer changed it to. Shown to the pharmacist
 * as the prescription's edit history.
 */
@Entity
@Table(name = "prescription_edits", indexes = @Index(columnList = "prescription_id"))
@Getter @Setter @NoArgsConstructor
public class PrescriptionEdit {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Prescription prescription;

    /** Short field name: "Patient", "Doctor", "Rx date", "Med 2 dose". */
    @Column(nullable = false, length = 40)
    private String field;

    /** What the AI read for it; null when the AI didn't read it (or wasn't on). */
    @Column(length = 300)
    private String aiValue;

    /** What it said just before this change. */
    @Column(length = 300)
    private String oldValue;

    /** What the customer changed it to; null when they cleared it or removed the medicine. */
    @Column(length = 300)
    private String newValue;

    /** Who changed it, as their name. */
    @Column(length = 120)
    private String editedBy;

    private LocalDateTime editedAt = LocalDateTime.now();

    public PrescriptionEdit(Prescription prescription, String field, String aiValue, String oldValue, String newValue, String editedBy) {
        this.prescription = prescription;
        this.field = field;
        this.aiValue = cut(aiValue);
        this.oldValue = cut(oldValue);
        this.newValue = cut(newValue);
        this.editedBy = editedBy == null ? null : editedBy.length() > 120 ? editedBy.substring(0, 120) : editedBy;
    }

    private static String cut(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > 300 ? t.substring(0, 299) + "…" : t;
    }
}
