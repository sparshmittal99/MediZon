package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Packs of an order line taken from one prescription. A cart can mix medicines from several prescriptions, and a line
 * can even be split across two, so each line records exactly which prescription medicines it used: cancelling the
 * order gives those packs back, and the invoice names the right doctor for each medicine.
 */
@Entity
@Table(name = "prescription_uses")
@Getter @Setter @NoArgsConstructor
public class PrescriptionUse {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private OrderItem orderItem;

    @ManyToOne(optional = false)
    private PrescriptionItem prescriptionItem;

    private int packs;

    public PrescriptionUse(OrderItem orderItem, PrescriptionItem prescriptionItem, int packs) {
        this.orderItem = orderItem;
        this.prescriptionItem = prescriptionItem;
        this.packs = packs;
    }

    public Prescription getPrescription() { return prescriptionItem.getPrescription(); }
}
