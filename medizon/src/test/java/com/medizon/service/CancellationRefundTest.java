package com.medizon.service;

import com.medizon.domain.Order;
import com.medizon.domain.PaymentMethod;
import com.medizon.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Cancelling refunds everything paid except the charge for the way it was paid, which the gateway keeps. */
class CancellationRefundTest {

    private static Order paid(PaymentMethod method, String total, String fee, String wallet) {
        Order o = new Order();
        o.setOrderNumber("MZ1001");
        o.setPaymentMethod(method);
        o.setPaymentStatus(PaymentStatus.PAID);
        o.setGatewayPaymentId("pay_1");
        o.setTotal(new BigDecimal(total));
        o.setPaymentFee(new BigDecimal(fee));
        o.setWalletUsed(new BigDecimal(wallet));
        return o;
    }

    @Test
    void aCardOrderGetsEverythingBackButTheCardCharge() {
        // ₹120 of medicines + ₹11.80 card charge, all by card
        OrderService.Refund r = OrderService.cancellationRefund(paid(PaymentMethod.CARD, "131.80", "11.80", "0"));
        assertThat(r.online()).isEqualByComparingTo("120.00");
        assertThat(r.wallet()).isEqualByComparingTo("0");
        assertThat(r.kept()).isEqualByComparingTo("11.80");
    }

    @Test
    void mediZonMoneyComesBackInFullWhenTheCardPaidTheCharge() {
        // ₹50 MediZon Money, ₹81.80 by card (₹70 + ₹11.80 charge)
        OrderService.Refund r = OrderService.cancellationRefund(paid(PaymentMethod.CARD, "131.80", "11.80", "50"));
        assertThat(r.online()).isEqualByComparingTo("70.00");
        assertThat(r.wallet()).isEqualByComparingTo("50");
        assertThat(r.kept()).isEqualByComparingTo("11.80");
    }

    @Test
    void whatTheCardDidntCoverOfTheChargeComesOffTheMediZonMoney() {
        // Only ₹5 went by card: the other ₹6.80 of the charge is kept from the MediZon Money
        OrderService.Refund r = OrderService.cancellationRefund(paid(PaymentMethod.CARD, "131.80", "11.80", "126.80"));
        assertThat(r.online()).isEqualByComparingTo("0");
        assertThat(r.wallet()).isEqualByComparingTo("120.00");
        assertThat(r.kept()).isEqualByComparingTo("11.80");
    }

    @Test
    void aFreeWayToPayIsRefundedInFull() {
        OrderService.Refund r = OrderService.cancellationRefund(paid(PaymentMethod.UPI, "120.00", "0", "0"));
        assertThat(r.online()).isEqualByComparingTo("120.00");
        assertThat(r.kept()).isEqualByComparingTo("0");
    }

    @Test
    void anUnpaidPayOnDeliveryOrderOnlyGetsItsMediZonMoneyBack() {
        Order o = paid(PaymentMethod.COD, "100.00", "0", "30");
        o.setPaymentStatus(PaymentStatus.PENDING);
        o.setGatewayPaymentId(null);
        OrderService.Refund r = OrderService.cancellationRefund(o);
        assertThat(r.online()).isEqualByComparingTo("0");
        assertThat(r.wallet()).isEqualByComparingTo("30");
        assertThat(r.kept()).isEqualByComparingTo("0");
    }
}
