package com.medizon.payment;

import com.medizon.domain.Order;
import com.medizon.domain.PaymentMethod;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayGatewayTest {

    private final RazorpayGateway gateway = new RazorpayGateway("rzp_test_key", "test_secret", "http://localhost:1");

    /** What Razorpay sends: HMAC-SHA256 of "order id|payment id" with the key secret, in hex. */
    private static String razorpaySignature(String orderId, String paymentId, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void trustsOnlyRazorpaysSignature() throws Exception {
        String good = razorpaySignature("order_ABC", "pay_XYZ", "test_secret");
        assertThat(gateway.isGenuine("order_ABC", "pay_XYZ", good)).isTrue();
        assertThat(gateway.isGenuine("order_ABC", "pay_XYZ", good.toUpperCase())).isTrue();
        // Another payment, another order, someone else's secret, or nothing at all
        assertThat(gateway.isGenuine("order_ABC", "pay_OTHER", good)).isFalse();
        assertThat(gateway.isGenuine("order_OTHER", "pay_XYZ", good)).isFalse();
        assertThat(gateway.isGenuine("order_ABC", "pay_XYZ", razorpaySignature("order_ABC", "pay_XYZ", "guess"))).isFalse();
        assertThat(gateway.isGenuine("order_ABC", "pay_XYZ", null)).isFalse();
    }

    @Test
    void withoutKeysNothingIsGenuine() throws Exception {
        RazorpayGateway off = new RazorpayGateway("", "", "http://localhost:1");
        assertThat(off.isConfigured()).isFalse();
        assertThat(off.isGenuine("order_ABC", "pay_XYZ", razorpaySignature("order_ABC", "pay_XYZ", ""))).isFalse();
    }

    @Test
    void onlyUpiAndCardsGoThroughRazorpay() {
        assertThat(PaymentMethod.UPI.isOnline()).isTrue();
        assertThat(PaymentMethod.CARD.isOnline()).isTrue();
        assertThat(PaymentMethod.NETBANKING.isOnline()).isTrue();
        assertThat(PaymentMethod.COD.isOnline()).isFalse();
        assertThat(PaymentMethod.WALLET.isOnline()).isFalse();
        assertThat(PaymentMethod.online()).containsExactly(PaymentMethod.UPI, PaymentMethod.CARD, PaymentMethod.NETBANKING);
        assertThat(RazorpayGateway.checkoutMethod(PaymentMethod.NETBANKING)).isEqualTo("netbanking");
    }

    @Test
    void eachGatewayOffersOnlyTheMethodsItsAccountTakes() {
        // Razorpay without KYC: cards and net banking; anything that isn't an online method is ignored
        assertThat(RazorpayGateway.methods("CARD, netbanking")).containsExactly(PaymentMethod.CARD, PaymentMethod.NETBANKING);
        assertThat(RazorpayGateway.methods("NETBANKING,UPI,COD")).containsExactly(PaymentMethod.UPI, PaymentMethod.NETBANKING);
        assertThat(new RazorpayGateway("k", "s", "http://localhost:1").methods()).containsExactly(PaymentMethod.CARD, PaymentMethod.NETBANKING);
    }

    @Test
    void refundsGoBackOnlyForOrdersPaidOnline() {
        Order o = new Order();
        assertThat(o.isPaidOnline()).isFalse();
        o.setGatewayPaymentId("pay_XYZ");
        assertThat(o.isPaidOnline()).isTrue();
    }

    @Test
    void upiGoesToMediZonUpiUntilRazorpayTakesIt() {
        PaymentGateways gateways = new PaymentGateways(java.util.List.of(new RazorpayGateway("k", "s", "http://localhost:1"), new MediZonUpiGateway()));
        assertThat(gateways.forMethod(PaymentMethod.UPI).orElseThrow().id()).isEqualTo(Gateway.MEDIZON_UPI);
        assertThat(gateways.forMethod(PaymentMethod.CARD).orElseThrow().id()).isEqualTo(Gateway.RAZORPAY);
        assertThat(gateways.onlineMethods()).containsExactly(PaymentMethod.UPI, PaymentMethod.CARD, PaymentMethod.NETBANKING);
        // Without Razorpay's keys only UPI is offered online
        PaymentGateways upiOnly = new PaymentGateways(java.util.List.of(new RazorpayGateway("", "", "http://localhost:1"), new MediZonUpiGateway()));
        assertThat(upiOnly.onlineMethods()).containsExactly(PaymentMethod.UPI);
    }
}
