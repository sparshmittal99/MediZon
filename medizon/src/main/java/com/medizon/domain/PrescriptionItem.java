package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A medicine an approved prescription covers, as the pharmacist confirmed it: how many packs were prescribed and how
 * many have been ordered against it so far (manually or by automatic refills).
 */
@Entity
@Table(name = "prescription_items", uniqueConstraints = @UniqueConstraint(columnNames = {"prescription_id", "medicine_id"}))
@Getter @Setter @NoArgsConstructor
public class PrescriptionItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Prescription prescription;

    @ManyToOne(optional = false)
    private Medicine medicine;

    /** Packs prescribed, as the pharmacist approved. */
    private int packs;

    /** Packs ordered against this prescription; cancelled orders give theirs back. */
    private int packsOrdered;

    /** On a digital prescription: how many to take morning, afternoon and night, "1-0-1". */
    @Column(length = 20)
    private String dosage;

    /** On a digital prescription: when and for how long, "After food · 5 days". */
    @Column(length = 120)
    private String instructions;

    public PrescriptionItem(Prescription prescription, Medicine medicine, int packs) {
        this.prescription = prescription;
        this.medicine = medicine;
        this.packs = packs;
    }

    public int getRemaining() { return Math.max(0, packs - packsOrdered); }

    public boolean isUsedUp() { return packsOrdered >= packs; }
}
