package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.OrderRepository;
import com.medizon.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Orders placed waiting for their prescription: who can confirm them, approval, rejection and the consultation. */
class PrescriptionCheckTest {

    private OrderRepository orders;
    private OrderService orderService;
    private PrescriptionService prescriptionService;
    private SupportService supportService;
    private PrescriptionCheckService service;
    private Medicine acitrom, dolo;
    private User customer, pharmacist;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        orderService = mock(OrderService.class);
        prescriptionService = mock(PrescriptionService.class);
        supportService = mock(SupportService.class);
        PrescriptionPdfService pdf = mock(PrescriptionPdfService.class);
        when(pdf.render(any())).thenReturn(new byte[]{1});
        service = new PrescriptionCheckService(orders, orderService, prescriptionService, supportService,
                mock(SupportTicketRepository.class), mock(NotificationService.class), mock(StaffAlerts.class), pdf);
        acitrom = medicine(1, "Acitrom 3", true);
        dolo = medicine(2, "Dolo 650", false);
        customer = user(10, Role.CUSTOMER, "Asha Sharma");
        pharmacist = user(11, Role.PHARMACIST, "Priya Shah");
    }

    @Test
    void aStoreCantConfirmAnOrderWhosePrescriptionIsPendingButTheAdminCan() {
        Order o = order(1, RxCheck.UPLOAD);
        assertThat(o.nextStatusesFor(false)).containsExactly(OrderStatus.CANCELLED);
        assertThat(o.nextStatusesFor(true)).containsExactly(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);

        o.setRxReview(RxReview.APPROVED);
        assertThat(o.nextStatusesFor(false)).containsExactly(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);
    }

    @Test
    void onlyThePrescriptionMedicinesNotTakenFromAPrescriptionAreUncovered() {
        Order o = order(1, RxCheck.UPLOAD);
        assertThat(OrderService.hasUncoveredRx(o)).isTrue();
        assertThat(OrderService.uncoveredPacks(o.getItems().get(1))).isFalse();      // Dolo needs no prescription
    }

    @Test
    void rejectingTheUploadCancelsTheOrderSayingWhy() {
        Order o = order(1, RxCheck.UPLOAD);
        Prescription upload = upload(o);
        when(orders.findAwaiting(upload.getId(), OrderStatus.CANCELLED)).thenReturn(List.of(o));

        service.onRejected(new PrescriptionService.Rejected(upload.getId(), "Blurry photo"));

        assertThat(o.getRxReview()).isEqualTo(RxReview.REJECTED);
        assertThat(o.getCancelReason()).isEqualTo(PrescriptionCheckService.UPLOAD_REJECTED + " Pharmacist's note: Blurry photo");
        verify(orderService).updateStatus(o.getId(), OrderStatus.CANCELLED);
    }

    @Test
    void approvingTheUploadTakesTheMedicinesFromItAndLetsTheStoreConfirm() {
        Order o = order(1, RxCheck.UPLOAD);
        Prescription upload = upload(o);
        upload.setStatus(PrescriptionStatus.APPROVED);
        upload.getItems().add(new PrescriptionItem(upload, acitrom, 2));
        when(orders.findAwaiting(upload.getId(), OrderStatus.CANCELLED)).thenReturn(List.of(o));

        service.onApproved(new PrescriptionService.Approved(upload.getId()));

        assertThat(o.getRxReview()).isEqualTo(RxReview.APPROVED);
        assertThat(OrderService.hasUncoveredRx(o)).isFalse();
        assertThat(upload.getItems().get(0).getPacksOrdered()).isEqualTo(2);
        assertThat(o.nextStatusesFor(false)).contains(OrderStatus.CONFIRMED);
        verify(orderService, never()).updateStatus(anyLong(), any());
    }

    @Test
    void anOrderWaitsUntilEveryFileSentForItIsReviewed() {
        Order o = order(1, RxCheck.UPLOAD);
        Prescription first = upload(o), second = upload(o);
        first.setStatus(PrescriptionStatus.APPROVED);
        when(orders.findAwaiting(first.getId(), OrderStatus.CANCELLED)).thenReturn(List.of(o));

        service.onApproved(new PrescriptionService.Approved(first.getId()));

        assertThat(second.getStatus()).isEqualTo(PrescriptionStatus.PENDING);
        assertThat(o.getRxReview()).isEqualTo(RxReview.PENDING);
    }

    @Test
    void theChatOpensWithThePharmacistsGreetingAndThePrescriptionMedicines() {
        Order o = order(1, RxCheck.CONSULT);
        SupportTicket t = consult(o);
        when(supportService.getForStaff(t.getId(), pharmacist)).thenReturn(t);

        service.startChat(t.getId(), pharmacist);

        assertThat(t.isChatStarted()).isTrue();
        TicketMessage first = t.getMessages().get(t.getMessages().size() - 1);
        assertThat(first.getKind()).isEqualTo(TicketMessageKind.STAFF);
        assertThat(first.getBody()).startsWith("Hello, I am MediZon pharmacist. This chat is regarding your order MZ1.")
                .contains("• Acitrom 3 × 2").doesNotContain("Dolo 650");
    }

    @Test
    void approvingTheConsultationIssuesADigitalPrescriptionOnce() {
        Order o = order(1, RxCheck.CONSULT);
        SupportTicket t = consult(o);
        t.setChatStartedAt(java.time.LocalDateTime.now());
        when(supportService.getForStaff(t.getId(), pharmacist)).thenReturn(t);
        when(prescriptionService.issueDigital(eq(customer), eq("Asha Sharma"), eq("Priya Shah"), any(), anyList(), any()))
                .thenAnswer(a -> {
                    Prescription rx = new Prescription();
                    rx.setId(99L);
                    rx.setUser(customer);
                    rx.setDigital(true);
                    rx.setStatus(PrescriptionStatus.APPROVED);
                    rx.setValidUntil(LocalDate.now().plusMonths(6));
                    List<PrescriptionService.DigitalLine> lines = a.getArgument(4);
                    lines.forEach(l -> {
                        PrescriptionItem i = new PrescriptionItem(rx, l.medicine(), l.packs());
                        i.setDosage(l.dosage());
                        rx.getItems().add(i);
                    });
                    return rx;
                });

        service.approve(t.getId(), pharmacist, "Asha Sharma", null,
                List.of(new PrescriptionCheckService.Dose(acitrom.getId(), "1", "0", "1", "After food", 5)));

        assertThat(o.getRxReview()).isEqualTo(RxReview.APPROVED);
        assertThat(OrderService.hasUncoveredRx(o)).isFalse();
        assertThat(t.getConsultReview()).isEqualTo(RxReview.APPROVED);
        assertThat(t.getStatus()).isEqualTo(TicketStatus.CLOSED);
        verify(prescriptionService).issueDigital(any(), any(), any(), any(),
                argThat(l -> l.size() == 1 && l.get(0).dosage().equals("1-0-1") && l.get(0).instructions().equals("After food · 5 days")), any());
        // Decided: it can't be approved or rejected again
        assertThatThrownBy(() -> service.reject(t.getId(), pharmacist)).isInstanceOf(BusinessException.class);
    }

    @Test
    void aDoseIsNeededForEveryMedicine() {
        Order o = order(1, RxCheck.CONSULT);
        SupportTicket t = consult(o);
        t.setChatStartedAt(java.time.LocalDateTime.now());
        when(supportService.getForStaff(t.getId(), pharmacist)).thenReturn(t);

        assertThatThrownBy(() -> service.approve(t.getId(), pharmacist, "Asha Sharma", null,
                List.of(new PrescriptionCheckService.Dose(acitrom.getId(), "0", "0", "0", "After food", 5))))
                .hasMessage("Choose at least one dose of Acitrom 3.");
        verify(prescriptionService, never()).issueDigital(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    void rejectingTheConsultationCancelsTheOrder() {
        Order o = order(1, RxCheck.CONSULT);
        SupportTicket t = consult(o);
        t.setChatStartedAt(java.time.LocalDateTime.now());
        when(supportService.getForStaff(t.getId(), pharmacist)).thenReturn(t);

        service.reject(t.getId(), pharmacist);

        assertThat(o.getCancelReason()).isEqualTo(PrescriptionCheckService.CONSULT_REJECTED);
        assertThat(t.getConsultReview()).isEqualTo(RxReview.REJECTED);
        verify(orderService).updateStatus(o.getId(), OrderStatus.CANCELLED);
    }

    // ------------------------------------------------------------------ helpers

    private Order order(long id, RxCheck check) {
        Order o = new Order();
        o.setId(id);
        o.setOrderNumber("MZ" + id);
        o.setUser(customer);
        o.setTotal(new BigDecimal("100.00"));
        o.setRxCheck(check);
        o.setRxReview(RxReview.PENDING);
        o.addItem(item(acitrom, 2, "Asha Sharma"));
        o.addItem(item(dolo, 1, null));
        return o;
    }

    private SupportTicket consult(Order o) {
        SupportTicket t = new SupportTicket();
        t.setId(7L);
        t.setUser(customer);
        t.setOrder(o);
        t.setTopic(TicketTopic.PRESCRIPTION);
        t.setSubject("Prescription for order " + o.getOrderNumber());
        t.setStatus(TicketStatus.WITH_TEAM);
        t.setTeam(Role.PHARMACIST);
        t.setConsult(true);
        t.setConsultReview(RxReview.PENDING);
        return t;
    }

    private static long rxIds = 100;

    private Prescription upload(Order o) {
        Prescription p = new Prescription();
        p.setId(rxIds++);
        p.setUser(customer);
        p.setCheckoutUpload(true);
        p.setStatus(PrescriptionStatus.PENDING);
        p.setValidUntil(LocalDate.now().plusMonths(6));
        o.getAwaitedPrescriptions().add(p);
        return p;
    }

    private static OrderItem item(Medicine m, int qty, String patient) {
        OrderItem i = new OrderItem();
        i.setMedicine(m);
        i.setMedicineName(m.getName());
        i.setQuantity(qty);
        i.setPatientName(patient);
        return i;
    }

    private static Medicine medicine(long id, String name, boolean rx) {
        Medicine m = new Medicine();
        m.setId(id);
        m.setName(name);
        m.setPrice(new BigDecimal("30.00"));
        m.setPrescriptionRequired(rx);
        return m;
    }

    private static User user(long id, Role role, String name) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setFullName(name);
        return u;
    }
}
