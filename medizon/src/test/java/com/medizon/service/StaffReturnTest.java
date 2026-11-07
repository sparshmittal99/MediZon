package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.ReturnPickupForm;
import com.medizon.repository.ReturnRequestRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Returns raised by staff for the customer (e.g. after a support ticket), and returns cancelled by staff. */
class StaffReturnTest {

    private final ReturnRequestRepository returns = mock(ReturnRequestRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ReturnSettingsService settings = mock(ReturnSettingsService.class);
    { when(settings.get()).thenReturn(ReturnSettings.defaults()); }
    private final ReturnService service = new ReturnService(returns, orders, mock(MedicationPlanService.class),
            mock(FileStorageService.class), notifications, mock(StaffAlerts.class), mock(AddressService.class),
            mock(DeliveryAreaService.class), Validation.buildDefaultValidatorFactory().getValidator(), mock(WalletService.class),
            settings, mock(com.medizon.payment.PaymentGateways.class));

    private final User customer = user(Role.CUSTOMER, "Asha", "Rao");
    private final User storeStaff = user(Role.STORE, "Ravi", "Kumar");

    private static User user(Role role, String first, String last) {
        User u = new User();
        u.setRole(role);
        u.setFullName(first + " " + last);
        u.setEmail(first.toLowerCase() + "@medizon.test");
        return u;
    }

    private Order deliveredLongAgo(PaymentMethod paidBy) {
        Medicine m = new Medicine();
        m.setName("Insulin pen");
        m.setReturnable(false);
        OrderItem i = new OrderItem();
        i.setId(7L);
        i.setMedicine(m);
        i.setMedicineName("Insulin pen");
        i.setQuantity(2);
        i.setUnitPrice(new BigDecimal("400"));
        i.setLineTotal(new BigDecimal("800"));
        i.setReturnableAtOrder(false);
        Order o = new Order();
        o.setId(1L);
        o.setOrderNumber("MZ1");
        o.setUser(customer);
        o.setStatus(OrderStatus.DELIVERED);
        o.setDeliveredAt(LocalDateTime.now().minusDays(60));   // well past the customer's return window
        o.setPaymentMethod(paidBy);
        o.setReceiverName("Asha Rao");
        o.setShippingAddress("12 MG Road, Pune 411001");
        o.addItem(i);
        when(orders.get(1L)).thenReturn(o);
        when(returns.findByOrderOrderByCreatedAtDesc(o)).thenReturn(List.of());
        return o;
    }

    @Test
    void staffCanRaiseAReturnTheCustomerCouldNot() {
        Order o = deliveredLongAgo(PaymentMethod.CARD);
        assertThat(service.blocker(o)).isPresent();          // the customer can't: not returnable, window closed
        assertThat(service.staffBlocker(o)).isEmpty();       // staff can

        ReturnPickupForm refund = new ReturnPickupForm();
        refund.setRefundMode(RefundMode.ORIGINAL);
        ReturnRequest r = service.raiseForCustomer(storeStaff, 1L, List.of(7L), List.of(1), ReturnReason.DAMAGED,
                "Sorry for the trouble.", "Ticket SP-1: clearer photos after RT1 was rejected", LocalDate.now().plusDays(2), refund);

        // To the customer it is an ordinary approved return
        assertThat(r.getStatus()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(r.getUser()).isSameAs(customer);
        assertThat(r.getRefundAmount()).isEqualByComparingTo("400");
        assertThat(r.getRefundMode()).isEqualTo(RefundMode.ORIGINAL);
        assertThat(r.getPickupAddress()).isEqualTo("12 MG Road, Pune 411001");
        assertThat(r.getAdminNote()).isEqualTo("Sorry for the trouble.");
        verify(notifications).notify(eq(customer), eq(NotificationType.RETURN_UPDATE), eq("Return approved"), anyString(), any());
        // The back office keeps who raised it and why
        assertThat(r.isRaisedByStaff()).isTrue();
        assertThat(r.getRaisedBy()).isEqualTo("Ravi Kumar, Staff");
        assertThat(r.getStaffNote()).contains("SP-1");
    }

    @Test
    void staffMustSayWhy() {
        deliveredLongAgo(PaymentMethod.CARD);
        assertThatThrownBy(() -> service.raiseForCustomer(storeStaff, 1L, List.of(7L), List.of(1), ReturnReason.DAMAGED,
                null, "  ", null, new ReturnPickupForm()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Only staff see this");
    }

    @Test
    void notWhileAnotherReturnIsOpen() {
        Order o = deliveredLongAgo(PaymentMethod.CARD);
        ReturnRequest open = new ReturnRequest();
        open.setStatus(ReturnStatus.APPROVED);
        when(returns.findByOrderOrderByCreatedAtDesc(o)).thenReturn(List.of(open));
        assertThat(service.staffBlocker(o)).get().asString().contains("Cancel it first");
    }

    @Test
    void cancellingBeforePickupTellsTheCustomer() {
        Order o = deliveredLongAgo(PaymentMethod.CARD);
        ReturnRequest r = new ReturnRequest();
        r.setId(5L);
        r.setReturnNumber("RT5");
        r.setOrder(o);
        r.setUser(customer);
        r.setStatus(ReturnStatus.APPROVED);
        when(returns.findById(5L)).thenReturn(Optional.of(r));

        service.cancel(5L, storeStaff, "Raised by mistake");

        assertThat(r.getStatus()).isEqualTo(ReturnStatus.CANCELLED);
        assertThat(r.getCancelReason()).isEqualTo("Raised by mistake");
        assertThat(r.getCancelledBy()).isEqualTo("Ravi Kumar, Staff");
        assertThat(r.getCancelledAt()).isNotNull();
        assertThat(r.isOpen()).isFalse();
        verify(notifications).notify(eq(customer), eq(NotificationType.RETURN_UPDATE), eq("Return cancelled"), contains("Raised by mistake"), any());
    }

    @Test
    void cannotCancelOncePickedUpOrWithoutAReason() {
        ReturnRequest r = new ReturnRequest();
        r.setStatus(ReturnStatus.PICKED_UP);
        when(returns.findById(6L)).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.cancel(6L, storeStaff, "No longer needed"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("can't be cancelled");
        assertThatThrownBy(() -> service.cancel(6L, storeStaff, " "))
                .isInstanceOf(BusinessException.class).hasMessageContaining("customer sees this");
        assertThat(ReturnStatus.REQUESTED.isCancellable()).isTrue();
        assertThat(ReturnStatus.REFUNDED.isCancellable()).isFalse();
    }
}
