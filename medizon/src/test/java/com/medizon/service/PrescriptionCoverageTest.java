package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.CartItemRepository;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.PrescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covering a cart's prescription medicines from all of the customer's approved prescriptions, and how prescriptions
 * get used up and given back.
 */
class PrescriptionCoverageTest {

    private Medicine dolo, pan, acitrom, alprazolam;
    private Prescription forMe, forMom;
    private NotificationService notifications;
    private PrescriptionService service;

    private static Medicine medicine(long id, String name) {
        Medicine m = new Medicine();
        m.setId(id);
        m.setName(name);
        m.setPrescriptionRequired(true);
        return m;
    }

    private static Prescription approved(String patient, Object... medicineAndPacks) {
        Prescription p = new Prescription();
        p.setUser(new User());
        p.setStatus(PrescriptionStatus.APPROVED);
        p.setPatientName(patient);
        p.setDoctorName("Ambrose");
        p.setPrescribedOn(LocalDate.of(2026, 10, 4));
        p.setValidUntil(LocalDate.now().plusMonths(6));
        for (int k = 0; k < medicineAndPacks.length; k += 2) {
            p.getItems().add(new PrescriptionItem(p, (Medicine) medicineAndPacks[k], (Integer) medicineAndPacks[k + 1]));
        }
        return p;
    }

    private static PrescriptionService.RxLine line(Medicine m, int packs, String... patients) {
        return new PrescriptionService.RxLine(m, packs, Set.of(patients));
    }

    @BeforeEach
    void setUp() {
        dolo = medicine(1, "Dolo 650");
        pan = medicine(2, "Pantoprazole 40 mg");
        acitrom = medicine(3, "Acitrom 3");
        alprazolam = medicine(4, "Alprazolam 0.25");
        forMe = approved("Sparsh", dolo, 2, pan, 1);             // the newer approval: listed first
        forMom = approved("Asha Sharma", acitrom, 2, dolo, 1);
        notifications = mock(NotificationService.class);
        service = new PrescriptionService(mock(PrescriptionRepository.class), mock(MedicineRepository.class),
                mock(FileStorageService.class), notifications, mock(StaffAlerts.class), mock(ApplicationEventPublisher.class),
                mock(CartItemRepository.class), mock(MemberService.class));
    }

    @Test
    void aCartMixingSeveralPrescriptionsIsCoveredWithoutChoosingOne() {
        var c = PrescriptionService.cover(List.of(forMe, forMom), List.of(line(pan, 1, "Sparsh"), line(acitrom, 2, "Asha Sharma")));
        assertTrue(c.isComplete());
        assertSame(forMe, c.forMedicine(2L).get(0).item().getPrescription());
        assertSame(forMom, c.forMedicine(3L).get(0).item().getPrescription());
    }

    @Test
    void onlyWhatNoPrescriptionCoversIsRefused() {
        var c = PrescriptionService.cover(List.of(forMe, forMom), List.of(line(pan, 1), line(alprazolam, 1)));
        assertEquals(List.of("Alprazolam 0.25 is not on any of your approved prescriptions."), c.problems());
        assertFalse(c.forMedicine(2L).isEmpty());          // Pantoprazole is still fine
        BusinessException e = assertThrows(BusinessException.class, c::require);
        assertTrue(e.getMessage().startsWith("Alprazolam 0.25 is not on any of your approved prescriptions."));
    }

    @Test
    void thePatientsOwnPrescriptionIsUsedFirst() {
        // Dolo is on both; Mom's line takes it from Mom's prescription even though mine is listed first
        var c = PrescriptionService.cover(List.of(forMe, forMom), List.of(line(dolo, 1, "Asha Sharma")));
        assertSame(forMom, c.forMedicine(1L).get(0).item().getPrescription());
    }

    @Test
    void aLineCanBeSplitAcrossPrescriptions() {
        var c = PrescriptionService.cover(List.of(forMe, forMom), List.of(line(dolo, 3)));
        assertTrue(c.isComplete());
        assertEquals(2, c.forMedicine(1L).size());
        assertEquals(3, c.forMedicine(1L).stream().mapToInt(PrescriptionService.Take::packs).sum());
    }

    @Test
    void moreThanAllPrescriptionsAllowIsRefused() {
        var c = PrescriptionService.cover(List.of(forMe, forMom), List.of(line(dolo, 4)));
        assertEquals(List.of("Your prescriptions cover 3 more pack(s) of Dolo 650, and your cart has 4."), c.problems());
        assertTrue(c.forMedicine(1L).isEmpty());          // nothing taken for a line that can't be covered in full
    }

    @Test
    void aFullyOrderedMedicineIsRefused() {
        forMe.getItems().get(1).setPacksOrdered(1);       // all the Pantoprazole ordered already
        assertEquals(List.of("You've already ordered all the Pantoprazole 40 mg your prescriptions allow."),
                PrescriptionService.cover(List.of(forMe, forMom), List.of(line(pan, 1))).problems());
    }

    @Test
    void orderingEverythingUsesItUpAndTellsTheCustomer() {
        OrderItem doloLine = new OrderItem(), panLine = new OrderItem();
        var c = PrescriptionService.cover(List.of(forMe), List.of(line(dolo, 2), line(pan, 1)));
        PrescriptionService.use(doloLine, c.forMedicine(1L));
        PrescriptionService.use(panLine, c.forMedicine(2L));
        service.finishUse(c.prescriptions());

        assertEquals(PrescriptionStatus.USED_UP, forMe.getStatus());
        assertFalse(forMe.isValid());
        assertEquals("Dr. Ambrose, 4 Oct 2026", doloLine.getPrescribedBy());
        verify(notifications).notify(any(), eq(NotificationType.PRESCRIPTION_UPDATE), eq("Prescription used up"), anyString(), eq("/prescriptions"));
    }

    @Test
    void cancellingGivesThePacksBackToTheRightPrescriptions() {
        OrderItem doloLine = new OrderItem();
        doloLine.setMedicine(dolo);
        doloLine.setQuantity(3);
        var c = PrescriptionService.cover(List.of(forMe, forMom), List.of(line(dolo, 3)));
        PrescriptionService.use(doloLine, c.forMedicine(1L));
        assertEquals(0, forMe.remainingFor(1L));
        assertEquals(0, forMom.remainingFor(1L));
        Order o = new Order();
        o.getItems().add(doloLine);

        service.releaseUse(o);

        assertEquals(2, forMe.remainingFor(1L));
        assertEquals(1, forMom.remainingFor(1L));
    }

    @Test
    void prescriptionsApprovedBeforeMedicinesWereRecordedCantBeUsed() {
        Prescription old = new Prescription();
        old.setStatus(PrescriptionStatus.APPROVED);
        old.setValidUntil(LocalDate.now().plusMonths(3));
        assertTrue(old.isLegacy());
        assertFalse(old.isValid());
        assertEquals(0, old.remainingFor(dolo.getId()));
    }

    @Test
    void expiredPrescriptionsArentValid() {
        forMe.setValidUntil(LocalDate.now().minusDays(1));
        assertFalse(forMe.isValid());
    }
}
