package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.payment.Gateway;
import com.medizon.payment.MediZonUpiGateway;
import com.medizon.payment.PaymentGateway;
import com.medizon.payment.PaymentGateways;
import com.medizon.payment.RazorpayGateway;
import com.medizon.repository.CheckoutPaymentRepository;
import com.medizon.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A paid payment is placed by one path (the page, the webhook, the cart page or the reconciler) and must never also be
 * refunded by another: the bug where an order showed placed and paid while its payment was refunded.
 */
class PaymentServiceRaceTest {

    private final PaymentGateways gateways = mock(PaymentGateways.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final CheckoutPaymentRepository payments = mock(CheckoutPaymentRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderService orderService = mock(OrderService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PaymentService service = new PaymentService(gateways, mock(RazorpayGateway.class), mock(MediZonUpiGateway.class),
            payments, orders, orderService, notifications);

    private final User user = new User();
    private final CheckoutPayment payment = new CheckoutPayment();
    private final Order order = new Order();

    @BeforeEach
    void setUp() {
        user.setId(7L);
        payment.setId(1L);
        payment.setUser(user);
        payment.setGateway(Gateway.RAZORPAY);
        payment.setGatewayOrderId("order_ABC");
        payment.setMethod(PaymentMethod.CARD);
        payment.setAddressId(3L);
        payment.setAmount(new BigDecimal("120.00"));
        order.setId(5L);
        order.setOrderNumber("MZ1001");
        order.setTotal(new BigDecimal("120.00"));
        order.setPaymentMethod(PaymentMethod.CARD);

        when(gateways.get(any())).thenReturn(gateway);
        when(gateway.id()).thenReturn(Gateway.RAZORPAY);
        when(gateway.findPaid("order_ABC")).thenReturn(Optional.of(new PaymentGateway.Paid("pay_XYZ", "order_ABC", 12000, PaymentMethod.CARD)));
        when(payments.findWithUserByGatewayOrderId("order_ABC")).thenReturn(Optional.of(payment));
        when(payments.findById(1L)).thenReturn(Optional.of(payment));
        when(payments.moveStatus(eq(1L), eq(CheckoutPayment.Status.CREATED), eq(CheckoutPayment.Status.PLACING))).thenReturn(1);
    }

    @Test
    void aPaymentPlacedMeanwhileByAnotherPathIsNotRefunded() {
        // Not placed when this path starts; the other path's order lands while this one finds the cart empty
        when(orders.findByGatewayOrderIdOrderByIdAsc("order_ABC")).thenReturn(List.of(), List.of(), List.of(order));
        when(orderService.ready(any(), any(), any())).thenThrow(new BusinessException("Your cart is empty."));

        PaymentService.Check c = service.check(user, "order_ABC");

        verify(gateway, never()).refund(any(), any(), anyLong(), any());
        assertThat(payment.getStatus()).isEqualTo(CheckoutPayment.Status.PLACED);
        assertThat(c.state()).isEqualTo("PLACED");
    }

    @Test
    void aPaymentAlreadyPlacedIsMarkedPlacedNotPlacedAgain() {
        when(orders.findByGatewayOrderIdOrderByIdAsc("order_ABC")).thenReturn(List.of(), List.of(order));

        service.check(user, "order_ABC");

        verify(orderService, never()).checkout(any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        verify(gateway, never()).refund(any(), any(), anyLong(), any());
        assertThat(payment.getStatus()).isEqualTo(CheckoutPayment.Status.PLACED);
    }

    @Test
    void theReconcilerLeavesAPlacingInProgressAlone() {
        payment.setStatus(CheckoutPayment.Status.PLACING);
        when(payments.findToReconcile(any(), any(), any(), any())).thenReturn(List.of(payment));
        when(payments.takeOverStuck(eq(1L), eq(CheckoutPayment.Status.PLACING), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(0);

        service.reconcileOpen();

        verify(gateway, never()).findPaid(any());
        verify(gateway, never()).refund(any(), any(), anyLong(), any());
        verify(orderService, never()).checkout(any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void aPaymentWhoseOrdersCantBePlacedIsStillRefunded() {
        when(orders.findByGatewayOrderIdOrderByIdAsc("order_ABC")).thenReturn(List.of());
        when(orderService.ready(any(), any(), any())).thenThrow(new BusinessException("Paracetamol is out of stock."));
        when(gateway.refund(any(), any(), anyLong(), any())).thenReturn("rfnd_1");

        PaymentService.Check c = service.check(user, "order_ABC");

        verify(gateway).refund("order_ABC", "pay_XYZ", 12000, "Order couldn't be placed");
        assertThat(c.state()).isEqualTo("REFUNDED");
    }
}
