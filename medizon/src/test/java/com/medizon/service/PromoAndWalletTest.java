package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.Invoice;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromoAndWalletTest {

    @Test
    void promosRespectMinimumAndCap() {
        Coupon pct = new Coupon("HEALTH10", "10% off up to ₹100", null, Coupon.Kind.PERCENT, "10", "100", "299");
        assertThat(pct.discountOn(new BigDecimal("298.99"))).isEqualByComparingTo("0");
        assertThat(pct.discountOn(new BigDecimal("450"))).isEqualByComparingTo("45");
        assertThat(pct.discountOn(new BigDecimal("2500"))).isEqualByComparingTo("100");
        Coupon flat = new Coupon("FIRST50", "₹50 off", null, Coupon.Kind.FLAT, "50", null, "0");
        assertThat(flat.discountOn(new BigDecimal("30"))).isEqualByComparingTo("30");   // never more than the medicines
    }

    @Test
    void discountSplitAddsUpToThePaisa() {
        List<BigDecimal> shares = Discounts.split(new BigDecimal("100"),
                List.of(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("133.34")));
        assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100");
        assertThat(shares.get(2)).isGreaterThan(shares.get(0));
        assertThat(Discounts.split(BigDecimal.ZERO, List.of(BigDecimal.TEN))).containsExactly(new BigDecimal("0.00"));
    }

    @Test
    void invoiceTaxesThePriceAfterThePromo() {
        InvoiceService service = new InvoiceService(null);
        for (String f : new String[]{"sellerName", "sellerGstin", "sellerDrugLicense", "sellerAddress", "sellerPhone", "sellerEmail"}) {
            ReflectionTestUtils.setField(service, f, "");
        }
        ReflectionTestUtils.setField(service, "sellerState", "Maharashtra");
        Order o = new Order();
        o.setStatus(OrderStatus.SHIPPED);
        o.setInvoiceNumber("MZ/2026-27/00002");
        o.setInvoicedAt(LocalDateTime.now());
        o.setShippingState("Maharashtra");
        OrderItem i = new OrderItem();
        i.setMedicineName("Paracetamol");
        i.setUnitPrice(new BigDecimal("210"));
        i.setQuantity(1);
        i.setLineTotal(new BigDecimal("210"));
        i.setDiscount(new BigDecimal("50"));
        i.setGstRate(new BigDecimal("5"));
        i.setHsnCode("3004");
        o.addItem(i);
        o.setDeliveryFee(BigDecimal.ZERO);
        o.setCouponCode("FIRST50");
        o.setTotal(new BigDecimal("160"));

        Invoice inv = service.breakdown(o);
        assertThat(inv.isDiscounted()).isTrue();
        assertThat(inv.getCouponCode()).isEqualTo("FIRST50");
        assertThat(inv.getDiscountTotal()).isEqualByComparingTo("50");
        assertThat(inv.getTaxableTotal()).isEqualByComparingTo("152.38");   // 160 x 100 / 105
        assertThat(inv.getGrandTotal()).isEqualByComparingTo("160");
    }

    @Test
    void refundsArriveOnTheirDay() {
        ReturnSettings s = ReturnSettings.defaults();
        assertThat(RefundMode.WALLET.days(PaymentMethod.CARD, s)).isZero();
        assertThat(RefundMode.UPI.days(PaymentMethod.COD, s)).isEqualTo(2);
        assertThat(RefundMode.BANK.days(PaymentMethod.COD, s)).isEqualTo(7);
        assertThat(RefundMode.ORIGINAL.days(PaymentMethod.UPI, s)).isEqualTo(2);
        assertThat(RefundMode.ORIGINAL.days(PaymentMethod.CARD, s)).isEqualTo(7);
        assertThat(RefundMode.WALLET.timing(PaymentMethod.UPI, s)).isEqualTo("Instantly after pickup");
        s.setBankRefundDays(1);
        assertThat(RefundMode.BANK.timing(PaymentMethod.COD, s)).isEqualTo("1 day after pickup");
    }

    @Test
    void returnsRefundWhatWasPaidAfterThePromo() {
        OrderItem i = new OrderItem();
        i.setQuantity(3);
        i.setLineTotal(new BigDecimal("300"));
        i.setDiscount(new BigDecimal("31"));
        assertThat(ReturnService.paidFor(i, 1)).isEqualByComparingTo("89.66");
        assertThat(ReturnService.paidFor(i, 3)).isEqualByComparingTo("269");
    }

    @Test
    void paymentLabelShowsTheSplit() {
        Order o = new Order();
        o.setPaymentMethod(PaymentMethod.UPI);
        o.setTotal(new BigDecimal("200.00"));
        o.setWalletUsed(new BigDecimal("120.00"));
        assertThat(o.getAmountDue()).isEqualByComparingTo("80");
        assertThat(o.getPaymentLabel()).isEqualTo("MediZon Money ₹120.00 + UPI ₹80.00");
        o.setPaymentMethod(PaymentMethod.WALLET);
        assertThat(o.getPaymentLabel()).isEqualTo("MediZon Money");
    }
}
