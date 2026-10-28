package com.medizon.api;

import com.medizon.domain.PaymentMethod;
import com.medizon.service.CurrentUser;
import com.medizon.service.PaymentService;
import com.medizon.web.OrderController;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The payment page's calls. Online: {@code /pay} opens a payment with the gateway that takes the method, then
 * {@code /pay/confirm} (Razorpay) or {@code /upi/approve} (MediZon UPI) places the orders once it's paid. Pay on
 * delivery and MediZon Money: {@code /place}. Each answers where to go next.
 */
@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutApi {

    private final PaymentService paymentService;
    private final CurrentUser currentUser;

    /** What the customer chose on the payment page. */
    public record Choice(Long addressId, PaymentMethod method, boolean useWallet) {}

    /** What Razorpay Checkout hands back after a successful payment. */
    public record Paid(String orderId, String paymentId, String signature) {}

    /** A UPI ID to check, or a MediZon UPI request approved from it (no UPI ID when paid from a UPI app picked on the page). */
    public record Upi(String orderId, String upiId) {}

    @PostMapping("/pay")
    public PaymentService.Opened pay(@RequestBody Choice c, HttpSession session) {
        return paymentService.open(currentUser.get(), c.addressId(), c.method(), c.useWallet(), coupon(session));
    }

    @PostMapping("/pay/confirm")
    public PaymentService.Placed confirm(@RequestBody Paid p, HttpSession session) {
        return placed(paymentService.confirm(currentUser.get(), p.orderId(), p.paymentId(), p.signature()), session);
    }

    /** Checks a UPI ID before paying: {@code {upiId, name}}, the name being who it belongs to. */
    @PostMapping("/upi/verify")
    public Map<String, String> verifyUpi(@RequestBody Upi u) {
        String upiId = u.upiId() == null ? "" : u.upiId().trim();
        return Map.of("upiId", upiId, "name", paymentService.verifyUpiId(currentUser.get(), upiId));
    }

    @PostMapping("/upi/approve")
    public PaymentService.Placed approveUpi(@RequestBody Upi u, HttpSession session) {
        return placed(paymentService.approveUpi(currentUser.get(), u.orderId(), u.upiId()), session);
    }

    @PostMapping("/place")
    public PaymentService.Placed place(@RequestBody Choice c, HttpSession session) {
        return placed(paymentService.placeWithoutGateway(currentUser.get(), c.addressId(), c.method(), c.useWallet(), coupon(session)), session);
    }

    private static PaymentService.Placed placed(PaymentService.Placed placed, HttpSession session) {
        session.removeAttribute(OrderController.COUPON);
        return placed;
    }

    private static String coupon(HttpSession session) { return (String) session.getAttribute(OrderController.COUPON); }
}
