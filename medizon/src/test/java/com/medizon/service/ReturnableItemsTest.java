package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.ReturnPickupForm;
import jakarta.validation.Validation;
import com.medizon.repository.ReturnRequestRepository;
import com.medizon.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReturnableItemsTest {

    private final ReturnRequestRepository returns = mock(ReturnRequestRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final DeliveryAreaService areas = mock(DeliveryAreaService.class);
    private final ReturnService service = new ReturnService(returns, orders, mock(MedicationPlanService.class),
            mock(FileStorageService.class), mock(NotificationService.class), mock(StaffAlerts.class),
            mock(AddressService.class), areas, Validation.buildDefaultValidatorFactory().getValidator(), mock(WalletService.class));

    private static OrderItem item(long id, String name, Boolean returnableAtOrder, boolean medicineReturnable) {
        Medicine m = new Medicine();
        m.setName(name);
        m.setReturnable(medicineReturnable);
        OrderItem i = new OrderItem();
        i.setId(id);
        i.setMedicine(m);
        i.setMedicineName(name);
        i.setQuantity(2);
        i.setUnitPrice(new BigDecimal("50"));
        i.setLineTotal(new BigDecimal("100"));
        i.setReturnableAtOrder(returnableAtOrder);
        return i;
    }

    private static Order delivered(OrderItem... items) {
        Order o = new Order();
        o.setId(1L);
        o.setStatus(OrderStatus.DELIVERED);
        o.setDeliveredAt(LocalDateTime.now().minusDays(1));
        for (OrderItem i : items) o.addItem(i);
        return o;
    }

    @Test
    void onlyNonReturnableItemsMeansNoReturnOption() {
        Order o = delivered(item(1, "Insulin pen", false, false));
        assertThat(service.hasReturnableItems(o)).isFalse();
        assertThat(service.blocker(o)).contains("The items in this order can't be returned.");
    }

    @Test
    void mixedOrderOffersOnlyTheReturnableLines() {
        Order o = delivered(item(1, "Paracetamol", true, true), item(2, "Insulin pen", false, false));
        assertThat(service.hasReturnableItems(o)).isTrue();
        assertThat(service.blocker(o)).isEmpty();
        assertThat(service.returnable(o)).containsOnlyKeys(1L).containsEntry(1L, 2);
    }

    @Test
    void returnabilityIsWhatItWasWhenOrdered() {
        // Made non-returnable in the catalog after the order: the order keeps its promise
        assertThat(item(1, "Paracetamol", true, false).isReturnable()).isTrue();
        // Orders from before the setting existed follow the catalog
        assertThat(item(1, "Paracetamol", null, false).isReturnable()).isFalse();
        assertThat(item(1, "Paracetamol", null, true).isReturnable()).isTrue();
    }

    @Test
    void nonReturnableLinesCantBeRequested() {
        Order o = delivered(item(1, "Paracetamol", true, true), item(2, "Insulin pen", false, false));
        when(orders.getForUser(eq(1L), any())).thenReturn(o);
        assertThatThrownBy(() -> service.request(new User(), 1L, List.of(2L), List.of(1), ReturnReason.values()[0],
                "Damaged", photo(), new ReturnPickupForm()))
                .hasMessage("Insulin pen can't be returned.");
    }

    private static List<org.springframework.web.multipart.MultipartFile> photo() {
        return List.of(new MockMultipartFile("images", "pack.jpg", "image/jpeg", new byte[]{1}));
    }

    private Order cashOrder() {
        Order o = delivered(item(1, "Paracetamol", true, true));
        o.setPaymentMethod(PaymentMethod.COD);
        o.setShippingAddress("12 MG Road, Pune, Maharashtra, 411001");
        o.setReceiverName("Asha Kumar");
        when(orders.getForUser(eq(1L), any())).thenReturn(o);
        return o;
    }

    @Test
    void cashOrdersNeedSomewhereToSendTheRefund() {
        cashOrder();
        when(areas.isServiceable("411001")).thenReturn(true);
        assertThatThrownBy(() -> service.request(new User(), 1L, List.of(1L), List.of(1), ReturnReason.values()[0], "x", photo(), new ReturnPickupForm()))
                .hasMessageContaining("Choose MediZon Money, UPI or a bank account");

        ReturnPickupForm bank = new ReturnPickupForm();
        bank.setRefundMode(RefundMode.BANK);
        bank.setAccountName("A Kumar");
        bank.setAccountNumber("123456789012");
        bank.setConfirmAccountNumber("123456789013");
        bank.setIfsc("hdfc0001234");
        assertThatThrownBy(() -> service.request(new User(), 1L, List.of(1L), List.of(1), ReturnReason.values()[0], "x", photo(), bank))
                .hasMessage("The account numbers don't match.");
    }

    @Test
    void pickupFromTheDeliveryAddressNeedsItToBeServed() {
        cashOrder();
        when(areas.isServiceable("411001")).thenReturn(false);
        ReturnPickupForm upi = new ReturnPickupForm();
        upi.setRefundMode(RefundMode.UPI);
        upi.setUpiId("asha@okbank");
        assertThatThrownBy(() -> service.request(new User(), 1L, List.of(1L), List.of(1), ReturnReason.values()[0], "x", photo(), upi))
                .hasMessageContaining("can't pick up from PIN code 411001");
    }

    @Test
    void pickupDateStaysInTheBookingRange() {
        LocalDate today = LocalDate.now();
        for (int k = 0; k < 50; k++) {
            assertThat(service.pickupDate()).isBetween(today.plusDays(ReturnService.PICKUP_MIN_DAYS), today.plusDays(ReturnService.PICKUP_MAX_DAYS));
        }
        LocalDate shown = today.plusDays(2);
        assertThat(service.pickupDate(shown)).isEqualTo(shown);
        assertThat(service.pickupDate(today.minusDays(1))).isAfter(today);
    }

    @Test
    void refundGoesToTheMethodUsedOrTheOneGiven() {
        ReturnRequest r = new ReturnRequest();
        Order o = new Order();
        o.setPaymentMethod(PaymentMethod.UPI);
        r.setOrder(o);
        assertThat(r.getRefundTo()).isEqualTo("your UPI");
        o.setPaymentMethod(PaymentMethod.COD);
        r.setRefundMode(RefundMode.BANK);
        r.setRefundAccountNumber("123456789012");
        r.setRefundIfsc("HDFC0001234");
        assertThat(r.getRefundTo()).isEqualTo("bank account ••••9012 (HDFC0001234)");
        assertThat(ReturnService.orderPincode(cashOrder())).isEqualTo("411001");
    }
}
