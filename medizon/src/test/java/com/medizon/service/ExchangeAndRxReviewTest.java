package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.PrescriptionReading;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.PrescriptionRepository;
import com.medizon.repository.ReturnRequestRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exchanges (a free replacement instead of a refund), the admin's delivery charges, and the pharmacist's review rules. */
class ExchangeAndRxReviewTest {

    private static User user(Role role, long id) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setFullName("User " + id);
        u.setEmail("u" + id + "@medizon.test");
        return u;
    }

    @Nested
    class Exchanges {
        private final ReturnRequestRepository returns = mock(ReturnRequestRepository.class);
        private final OrderService orders = mock(OrderService.class);
        private final NotificationService notifications = mock(NotificationService.class);
        private final WalletService wallet = mock(WalletService.class);
        private final ReturnSettingsService settings = mock(ReturnSettingsService.class);
        { when(settings.get()).thenReturn(ReturnSettings.defaults()); }
        private final ReturnService service = new ReturnService(returns, orders, mock(MedicationPlanService.class),
                mock(FileStorageService.class), notifications, mock(StaffAlerts.class), mock(AddressService.class),
                mock(DeliveryAreaService.class), Validation.buildDefaultValidatorFactory().getValidator(), wallet,
                settings, mock(com.medizon.payment.PaymentGateways.class));
        private final User customer = user(Role.CUSTOMER, 1);

        private ReturnRequest exchange(ReturnStatus status) {
            Medicine m = new Medicine();
            m.setName("Dolo 650");
            OrderItem i = new OrderItem();
            i.setId(7L);
            i.setMedicine(m);
            i.setMedicineName("Dolo 650");
            i.setQuantity(2);
            i.setUnitPrice(new BigDecimal("30"));
            i.setLineTotal(new BigDecimal("60"));
            Order o = new Order();
            o.setId(1L);
            o.setOrderNumber("MZ1");
            o.setUser(customer);
            o.setStatus(OrderStatus.DELIVERED);
            o.setDeliveredAt(LocalDateTime.now().minusDays(1));
            o.setPaymentMethod(PaymentMethod.UPI);
            o.addItem(i);
            ReturnRequest r = new ReturnRequest();
            r.setId(5L);
            r.setKind(ReturnKind.EXCHANGE);
            r.setReturnNumber("EX5");
            r.setOrder(o);
            r.setUser(customer);
            r.setStatus(status);
            r.setPickupDate(LocalDate.now().plusDays(3));
            ReturnItem ri = new ReturnItem();
            ri.setOrderItem(i);
            ri.setQuantity(1);
            ri.setAmount(new BigDecimal("30"));
            r.addItem(ri);
            when(returns.findById(5L)).thenReturn(Optional.of(r));
            return r;
        }

        private Order replacement(OrderStatus status) {
            Order rep = new Order();
            rep.setId(2L);
            rep.setOrderNumber("MZ2");
            rep.setSource(OrderSource.EXCHANGE);
            rep.setStatus(status);
            rep.setExpectedBy(LocalDate.now().plusDays(2));
            return rep;
        }

        @Test
        void approvingSendsAFreeReplacementWithItsExpectedDate() {
            ReturnRequest r = exchange(ReturnStatus.REQUESTED);
            Order rep = replacement(OrderStatus.CONFIRMED);
            when(orders.createReplacement(r)).thenReturn(rep);

            service.decide(5L, true, null);

            assertThat(r.getStatus()).isEqualTo(ReturnStatus.APPROVED);
            assertThat(r.getReplacementOrder()).isSameAs(rep);
            assertThat(r.getRefundTo()).isNull();               // nothing is refunded
            verify(notifications).notify(eq(customer), eq(NotificationType.RETURN_UPDATE), eq("Exchange approved"),
                    contains("MZ2"), any());
        }

        @Test
        void pickupCompletesTheExchangeWithoutARefund() {
            ReturnRequest r = exchange(ReturnStatus.APPROVED);
            r.setReplacementOrder(replacement(OrderStatus.SHIPPED));

            service.markPickedUp(5L, false);

            assertThat(r.getStatus()).isEqualTo(ReturnStatus.EXCHANGED);
            assertThat(r.isOpen()).isFalse();
            verifyNoInteractions(wallet);
        }

        @Test
        void cancellingCallsOffAReplacementThatHasNotShipped() {
            ReturnRequest r = exchange(ReturnStatus.APPROVED);
            Order rep = replacement(OrderStatus.CONFIRMED);
            r.setReplacementOrder(rep);

            service.cancel(5L, user(Role.ADMIN, 9), "Customer changed their mind");

            assertThat(r.getStatus()).isEqualTo(ReturnStatus.CANCELLED);
            verify(orders).updateStatus(2L, OrderStatus.CANCELLED);
        }

        @Test
        void aShippedReplacementBlocksCancelling() {
            ReturnRequest r = exchange(ReturnStatus.APPROVED);
            r.setReplacementOrder(replacement(OrderStatus.SHIPPED));
            assertThatThrownBy(() -> service.cancel(5L, user(Role.ADMIN, 9), "x"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("already shipped");
        }

        /** The order of {@link #exchange}, delivered and ready for staff to raise one on. */
        private Order deliveredForStaff() {
            Order o = exchange(ReturnStatus.REQUESTED).getOrder();
            o.getItems().get(0).setReturnableAtOrder(true);
            when(orders.get(1L)).thenReturn(o);
            when(returns.findByOrderOrderByCreatedAtDesc(o)).thenReturn(List.of());
            return o;
        }

        @Test
        void staffCanRaiseAnExchangeThatShipsAtOnce() {
            deliveredForStaff();
            Order rep = replacement(OrderStatus.CONFIRMED);
            when(orders.createReplacement(any())).thenReturn(rep);

            ReturnRequest r = service.raiseForCustomer(user(Role.ADMIN, 9), 1L, ReturnKind.EXCHANGE, List.of(7L), List.of(1),
                    ReturnReason.DAMAGED, null, "Ticket SP-7: leaking bottle", LocalDate.now().plusDays(1), null);

            assertThat(r.isExchange()).isTrue();
            assertThat(r.getReturnNumber()).startsWith("EX");
            assertThat(r.getStatus()).isEqualTo(ReturnStatus.APPROVED);
            assertThat(r.getRefundAmount()).isEqualByComparingTo("0");
            assertThat(r.getRefundMode()).isNull();
            assertThat(r.getReplacementOrder()).isSameAs(rep);
            verify(notifications).notify(eq(customer), eq(NotificationType.RETURN_UPDATE), eq("Exchange approved"), contains("MZ2"), any());
        }

        @Test
        void aStaffExchangeOutOfStockRaisesNothing() {
            deliveredForStaff();
            when(orders.createReplacement(any())).thenThrow(new BusinessException("Only 0 pack(s) of Dolo 650 are in stock"));
            assertThatThrownBy(() -> service.raiseForCustomer(user(Role.ADMIN, 9), 1L, ReturnKind.EXCHANGE, List.of(7L), List.of(1),
                    ReturnReason.DAMAGED, null, "Ticket SP-7", null, null))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("in stock");
            verifyNoInteractions(notifications);
        }

        @Test
        void staffCantExchangeWhatIsNoLongerNeeded() {
            deliveredForStaff();
            assertThatThrownBy(() -> service.raiseForCustomer(user(Role.ADMIN, 9), 1L, ReturnKind.EXCHANGE, List.of(7L), List.of(1),
                    ReturnReason.NOT_NEEDED, null, "Ticket SP-7", null, null))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("raise a return");
        }

        @Test
        void exchangesAreForFaultyPacksOnly() {
            assertThat(ReturnKind.EXCHANGE.allows(ReturnReason.NOT_NEEDED)).isFalse();
            assertThat(ReturnKind.EXCHANGE.allows(ReturnReason.DAMAGED)).isTrue();
            assertThat(ReturnKind.RETURN.allows(ReturnReason.NOT_NEEDED)).isTrue();
            assertThat(ReturnKind.of("exchange")).isEqualTo(ReturnKind.EXCHANGE);
            assertThat(ReturnKind.of(null)).isEqualTo(ReturnKind.RETURN);
        }

        @Test
        void replacementOrdersHaveNothingToPayAndNoInvoice() {
            Order rep = replacement(OrderStatus.DELIVERED);
            assertThat(rep.isExchange()).isTrue();
            assertThat(rep.isInvoiceAvailable()).isFalse();
            assertThat(rep.isCancellable()).isFalse();
            assertThat(rep.getPaymentLabel()).contains("nothing to pay");
            assertThat(rep.getTrackSteps()).first().isEqualTo(OrderStatus.CONFIRMED);
        }
    }

    @Nested
    class DeliveryCharges {
        @Test
        void freeAtOrAboveTheAdminsThreshold() {
            OrderSettings s = OrderSettings.defaults();
            s.setFreeDeliveryAbove(new BigDecimal("300"));
            s.setDeliveryFee(new BigDecimal("25"));
            assertThat(s.deliveryFeeFor(new BigDecimal("299.99"))).isEqualByComparingTo("25");
            assertThat(s.deliveryFeeFor(new BigDecimal("300"))).isEqualByComparingTo("0");
            assertThat(s.deliveryFeeFor(BigDecimal.ZERO)).isEqualByComparingTo("0");   // empty cart
        }

        @Test
        void zeroMakesEveryDeliveryFree() {
            OrderSettings s = OrderSettings.defaults();
            s.setFreeDeliveryAbove(BigDecimal.ZERO);
            assertThat(s.isDeliveryAlwaysFree()).isTrue();
            assertThat(s.deliveryFeeFor(new BigDecimal("10"))).isEqualByComparingTo("0");
        }

        @Test
        void typosAreRefused() {
            OrderSettingsService service = new OrderSettingsService(mock(com.medizon.repository.OrderSettingsRepository.class));
            assertThatThrownBy(() -> service.saveDelivery(new BigDecimal("-1"), new BigDecimal("40"), "admin"))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.saveDelivery(new BigDecimal("499"), new BigDecimal("5000"), "admin"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    class PharmacistReview {
        private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
        private final MedicineRepository medicines = mock(MedicineRepository.class);
        private final FileStorageService storage = mock(FileStorageService.class);
        private final com.medizon.repository.OrderRepository orderRepo = mock(com.medizon.repository.OrderRepository.class);
        private final PrescriptionService service = new PrescriptionService(prescriptions, medicines, storage,
                mock(NotificationService.class), mock(StaffAlerts.class), mock(ApplicationEventPublisher.class),
                mock(com.medizon.repository.CartItemRepository.class), mock(MemberService.class), orderRepo);

        /** An order placed with prescription {@code 3} uploaded at checkout, waiting for it: 2 packs of medicine 11. */
        private void placedOrderWaiting(Medicine m) {
            m.setPrescriptionRequired(true);
            OrderItem i = new OrderItem();
            i.setMedicine(m);
            i.setMedicineName(m.getName());
            i.setQuantity(2);
            Order o = new Order();
            o.setOrderNumber("MZ9");
            o.setRxReview(RxReview.PENDING);
            o.addItem(i);
            when(orderRepo.findAwaiting(3L, OrderStatus.CANCELLED)).thenReturn(List.of(o));
        }

        @Test
        void aPharmacistCantChangeThePacksOfAPlacedOrder() {
            Prescription p = pending();
            placedOrderWaiting(medicine(11, "Dolo 650"));
            p.getOpenedBy().add(pharmacist.getId());

            // Whatever the form says, the order's 2 packs are approved
            service.review(3L, true, "Checked with the doctor.", null, Map.of(11L, 5), details, pharmacist);

            assertThat(p.getItems()).singleElement().satisfies(i -> assertThat(i.getPacks()).isEqualTo(2));
            assertThat(p.getReviewerNote()).isEqualTo("Checked with the doctor.");   // no adjustment, just their note
        }

        @Test
        void theAdminCanChangeThemAndTheCustomerIsTold() {
            Prescription p = pending();
            placedOrderWaiting(medicine(11, "Dolo 650"));

            service.review(3L, true, null, null, Map.of(11L, 1), details, user(Role.ADMIN, 1));

            assertThat(p.getItems()).singleElement().satisfies(i -> assertThat(i.getPacks()).isEqualTo(1));
            assertThat(p.getReviewerNote()).contains("Quantity adjusted according to the prescription: Dolo 650 2 → 1 pack");
        }

        @Test
        void aFullyOrderedDigitalPrescriptionIsUsedUpButKeepsItsPdf() {
            Prescription p = pending();
            p.setDigital(true);
            p.setStatus(PrescriptionStatus.APPROVED);
            p.setStoredFileName("rx-3.pdf");
            Medicine m = medicine(11, "Dolo 650");
            m.setPrescriptionRequired(true);
            PrescriptionItem item = new PrescriptionItem(p, m, 2);
            item.setPacksOrdered(2);
            p.getItems().add(item);

            service.finishUse(List.of(p));

            assertThat(p.getStatus()).isEqualTo(PrescriptionStatus.USED_UP);
            verify(storage, never()).delete(anyString());
        }
        private final User pharmacist = user(Role.PHARMACIST, 20);

        private Medicine medicine(long id, String name) {
            Medicine m = new Medicine();
            m.setId(id);
            m.setName(name);
            when(medicines.findById(id)).thenReturn(Optional.of(m));
            return m;
        }

        private Prescription pending() {
            Prescription p = new Prescription();
            p.setId(3L);
            p.setUser(user(Role.CUSTOMER, 1));
            p.setStatus(PrescriptionStatus.PENDING);
            when(prescriptions.findById(3L)).thenReturn(Optional.of(p));
            return p;
        }

        private final PrescriptionService.Details details = new PrescriptionService.Details("Ambrose", "Asha Rao", LocalDate.now().minusDays(1));

        @Test
        void aPharmacistMustOpenTheFileBeforeApproving() {
            Prescription p = pending();
            medicine(11, "Dolo 650");
            assertThatThrownBy(() -> service.review(3L, true, null, null, Map.of(11L, 1), details, pharmacist))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("Open the prescription file");

            service.recordOpened(3L, pharmacist);
            service.review(3L, true, null, null, Map.of(11L, 1), details, pharmacist);
            assertThat(p.getStatus()).isEqualTo(PrescriptionStatus.APPROVED);
        }

        @Test
        void theAdminCanApproveWithoutOpeningIt() {
            Prescription p = pending();
            medicine(11, "Dolo 650");
            service.review(3L, true, null, null, Map.of(11L, 1), details, user(Role.ADMIN, 1));
            assertThat(p.getStatus()).isEqualTo(PrescriptionStatus.APPROVED);
        }

        @Test
        void changedPacksTellTheCustomerWhy() {
            Prescription p = pending();
            medicine(11, "Dolo 650");
            medicine(12, "Azee 500");
            p.getRequestedPacks().put(11L, 3);
            p.getRequestedPacks().put(12L, 1);
            p.getOpenedBy().add(pharmacist.getId());

            service.review(3L, true, "Take after food.", null, Map.of(11L, 2), details, pharmacist);

            assertThat(p.getReviewerNote())
                    .startsWith("Quantity adjusted according to the prescription:")
                    .contains("Dolo 650 3 → 2 packs").contains("Azee 500 is not on the prescription")
                    .endsWith("Take after food.");
        }

        @Test
        void noNoteWhenThePacksMatch() {
            Prescription p = pending();
            medicine(11, "Dolo 650");
            p.getRequestedPacks().put(11L, 2);
            service.review(3L, true, null, null, Map.of(11L, 2), details, user(Role.ADMIN, 1));
            assertThat(p.getReviewerNote()).isNull();
        }
    }

    @Nested
    class CustomerEdits {
        private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
        private final PrescriptionScanService scan = mock(PrescriptionScanService.class);
        private final PrescriptionEditService service = new PrescriptionEditService(prescriptions, mock(MedicineRepository.class), scan);
        private final User customer = user(Role.CUSTOMER, 1);

        @Test
        void eachChangedFieldIsLoggedWithWhatTheAiRead() {
            Prescription p = new Prescription();
            p.setId(4L);
            p.setUser(customer);
            p.setStatus(PrescriptionStatus.DRAFT);
            p.setPatientName("Asa Rao");
            p.setDoctorName("Ambrose");
            PrescriptionReading ai = new PrescriptionReading("Ambrose", null, "Asa Rao", null, null, true, true, List.of(),
                    List.of(new PrescriptionReading.Line("Dolo 650", null, "650 mg", "1-0-1", "5 days", 10, 11L, "high")));
            when(prescriptions.findByIdAndUser(4L, customer)).thenReturn(Optional.of(p));
            when(scan.reading(p)).thenReturn(Optional.of(ai));
            when(scan.current(p)).thenReturn(Optional.of(ai));
            when(scan.write(any())).thenReturn("{}");

            int changed = service.save(customer, 4L, new PrescriptionEditService.Form("Asha Rao", "Ambrose", null, null,
                    List.of(new PrescriptionEditService.Med("Dolo 650", "650 mg", "1-1-1", "5 days"))));

            assertThat(changed).isEqualTo(2);
            assertThat(p.getPatientName()).isEqualTo("Asha Rao");
            assertThat(p.getEdits()).extracting(PrescriptionEdit::getField).containsExactly("Patient", "Med 1 dose");
            PrescriptionEdit dose = p.getEdits().get(1);
            assertThat(dose.getAiValue()).isEqualTo("1-0-1");
            assertThat(dose.getNewValue()).isEqualTo("1-1-1");
            assertThat(dose.getEditedBy()).isEqualTo("User 1");
            assertThat(p.getCustomerReading()).isEqualTo("{}");     // the AI's own reading is kept as it was
        }

        @Test
        void reviewedPrescriptionsCantBeEdited() {
            Prescription p = new Prescription();
            p.setUser(customer);
            p.setStatus(PrescriptionStatus.APPROVED);
            when(prescriptions.findByIdAndUser(4L, customer)).thenReturn(Optional.of(p));
            assertThatThrownBy(() -> service.editable(customer, 4L)).isInstanceOf(BusinessException.class);
        }
    }
}
