package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.CartItemRepository;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.PrescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uploads are drafts the customer checks first; sending one records what's in the cart for its patient. */
class PrescriptionDraftTest {

    private final User user = new User();
    private PrescriptionRepository prescriptions;
    private CartItemRepository cartItems;
    private MemberService members;
    private StaffAlerts staffAlerts;
    private PrescriptionService service;
    private Member me, mom, others;
    private Medicine acitrom, metformin, vitaminC;

    private static Member member(long id, MemberKind kind, String name) {
        Member m = new Member();
        m.setId(id);
        m.setKind(kind);
        m.setName(name);
        return m;
    }

    private static Medicine medicine(long id, boolean rx) {
        Medicine m = new Medicine();
        m.setId(id);
        m.setName("Medicine " + id);
        m.setPrescriptionRequired(rx);
        return m;
    }

    private CartItem line(Medicine m, Map<Member, Integer> packs) {
        CartItem c = new CartItem();
        c.setMedicine(m);
        c.setQuantity(packs.values().stream().mapToInt(Integer::intValue).sum());
        packs.forEach((who, n) -> {
            c.getMembers().add(who);
            c.getPacks().put(who.getId(), n);
        });
        return c;
    }

    @BeforeEach
    void setUp() {
        prescriptions = mock(PrescriptionRepository.class);
        cartItems = mock(CartItemRepository.class);
        members = mock(MemberService.class);
        staffAlerts = mock(StaffAlerts.class);
        service = new PrescriptionService(prescriptions, mock(MedicineRepository.class), mock(FileStorageService.class),
                mock(NotificationService.class), staffAlerts, mock(ApplicationEventPublisher.class), cartItems, members);
        me = member(1, MemberKind.SELF, "Sparsh");
        mom = member(2, MemberKind.FAMILY, "Mummy");
        others = member(3, MemberKind.OTHERS, "Others");
        when(members.others(user)).thenReturn(others);
        acitrom = medicine(10, true);
        metformin = medicine(11, true);
        vitaminC = medicine(12, false);
        when(cartItems.findByUserOrderByIdAsc(user)).thenReturn(List.of(
                line(acitrom, Map.of(me, 2)),
                line(metformin, Map.of(mom, 3, me, 1)),
                line(vitaminC, Map.of(mom, 1))));
    }

    private Prescription draft(String patient) {
        Prescription p = new Prescription();
        p.setId(5L);
        p.setUser(user);
        p.setStatus(PrescriptionStatus.DRAFT);
        p.setPatientName(patient);
        return p;
    }

    @Test
    void takesTheCartLinesForThePatientOnIt() {
        when(members.byPatientName(user, "Asha Sharma")).thenReturn(Optional.of(mom));
        assertEquals(Map.of(11L, 3), service.requestedFromCart(user, draft("Asha Sharma")));    // Mom's share only, no OTC
    }

    @Test
    void takesEveryPrescriptionLineWhenThereIsNoPatientName() {
        assertEquals(Map.of(10L, 2, 11L, 4), service.requestedFromCart(user, draft(null)));
    }

    @Test
    void someoneOutsideTheFamilyWithNothingInTheCartForThemFallsBackToEverything() {
        when(members.byPatientName(user, "Priya Verma")).thenReturn(Optional.empty());
        assertEquals(Map.of(10L, 2, 11L, 4), service.requestedFromCart(user, draft("Priya Verma")));
    }

    @Test
    void sendingMovesItToThePharmacistWithTheDoctorsName() {
        Prescription p = draft(null);
        user.setFullName("Sparsh");
        when(prescriptions.findByIdAndUser(5L, user)).thenReturn(Optional.of(p));
        when(prescriptions.findById(5L)).thenReturn(Optional.of(p));      // for the pharmacists' alert
        when(cartItems.findByUserOrderByIdAsc(user)).thenReturn(List.of());

        service.submit(user, Map.of(5L, new PrescriptionService.Note("Dr. Ambrose", "  ")));

        assertEquals(PrescriptionStatus.PENDING, p.getStatus());
        assertEquals("Ambrose", p.getDoctorName());
        assertNull(p.getNotes());
        verify(staffAlerts).notify(eq(Role.PHARMACIST), any(), anyString(), anyString(), anyString());
    }

    @Test
    void anUnusedPrescriptionIsDeletedEvenIfApproved() {
        Prescription p = draft(null);
        p.setStatus(PrescriptionStatus.APPROVED);
        when(prescriptions.findByIdAndUser(5L, user)).thenReturn(Optional.of(p));
        when(prescriptions.usedForOrders(p)).thenReturn(false);

        assertFalse(service.remove(user, 5L));
        verify(prescriptions).delete(p);
    }

    @Test
    void aPrescriptionUsedForOrdersIsArchivedNotDeleted() {
        Prescription p = draft(null);
        p.setStatus(PrescriptionStatus.APPROVED);
        p.getItems().add(new PrescriptionItem(p, acitrom, 2));
        p.setValidUntil(java.time.LocalDate.now().plusMonths(3));
        assertTrue(p.isValid());
        when(prescriptions.findByIdAndUser(5L, user)).thenReturn(Optional.of(p));
        when(prescriptions.usedForOrders(p)).thenReturn(true);

        assertTrue(service.remove(user, 5L));
        verify(prescriptions, never()).delete(any());
        assertTrue(p.isRemoved());
        assertFalse(p.isValid());                       // can't be ordered with any more
    }

    @Test
    void medicinesAlreadyCoveredAreNotSentForReviewAgain() {
        // Acitrom (2 packs, mine) is covered by an approved prescription; only Metformin needs the new one
        Prescription approved = draft("Sparsh");
        approved.setStatus(PrescriptionStatus.APPROVED);
        approved.setValidUntil(java.time.LocalDate.now().plusMonths(3));
        approved.getItems().add(new PrescriptionItem(approved, acitrom, 2));
        when(prescriptions.findByUserAndStatusOrderByReviewedAtDesc(user, PrescriptionStatus.APPROVED)).thenReturn(List.of(approved));

        assertEquals(Map.of(11L, 4), service.requestedFromCart(user, draft(null)));
    }

    @Test
    void onlyThePartNotCoveredIsRequested() {
        Prescription approved = draft("Sparsh");
        approved.setStatus(PrescriptionStatus.APPROVED);
        approved.setValidUntil(java.time.LocalDate.now().plusMonths(3));
        approved.getItems().add(new PrescriptionItem(approved, metformin, 1));     // cart has 4
        when(prescriptions.findByUserAndStatusOrderByReviewedAtDesc(user, PrescriptionStatus.APPROVED)).thenReturn(List.of(approved));
        when(members.byPatientName(user, "Asha Sharma")).thenReturn(Optional.of(mom));

        assertEquals(Map.of(11L, 3), service.requestedFromCart(user, draft("Asha Sharma")));
    }
}
