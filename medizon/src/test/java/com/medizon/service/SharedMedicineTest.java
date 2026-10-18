package com.medizon.service;

import com.medizon.domain.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Two prescriptions for two people with the same medicine on both: each person gets, and pays from, their own. */
class SharedMedicineTest {

    private static Prescription approved(String patient, Medicine m, int packs) {
        Prescription p = new Prescription();
        p.setStatus(PrescriptionStatus.APPROVED);
        p.setPatientName(patient);
        p.setValidUntil(LocalDate.now().plusMonths(3));
        p.getItems().add(new PrescriptionItem(p, m, packs));
        return p;
    }

    @Test
    void oneCartLineForTwoPeopleIsCoveredByBothPrescriptions() {
        Medicine acitrom = new Medicine();
        acitrom.setId(3L);
        acitrom.setName("Acitrom 3");
        acitrom.setPrescriptionRequired(true);
        Prescription sparsh = approved("Sparsh", acitrom, 2), patientA = approved("Dummy Patient A", acitrom, 2);

        var c = PrescriptionService.cover(List.of(sparsh, patientA),
                List.of(new PrescriptionService.RxLine(acitrom, 4, Set.of("Sparsh", "Dummy Patient A"))));

        assertTrue(c.isComplete());
        assertEquals(2, sparsh.getItems().get(0).getRemaining());       // nothing used until the order is placed
        assertEquals(2, c.forMedicine(3L).size());
        assertEquals(Set.of(sparsh, patientA), c.prescriptions());
    }

    @Test
    void othersNamesAreKeptPerPerson() {
        assertEquals("Priya Verma", PrescriptionCartFiller.withPatient(null, "Priya Verma"));
        assertEquals("Priya Verma, Ravi Kumar", PrescriptionCartFiller.withPatient("Priya Verma", "Ravi Kumar"));
        assertEquals("Priya Verma, Ravi Kumar", PrescriptionCartFiller.withPatient("Priya Verma, Ravi Kumar", "MR. RAVI KUMAR"));

        assertTrue(PrescriptionCartFiller.hasPatient("Priya Verma, Ravi Kumar", "Ravi Kumar"));
        assertFalse(PrescriptionCartFiller.hasPatient("Priya Verma", "Ravi Kumar"));     // someone else's "Others" packs
        assertFalse(PrescriptionCartFiller.hasPatient(null, "Ravi Kumar"));
    }
}
