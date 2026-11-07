package com.medizon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medizon.payment.RazorpayGateway;
import com.medizon.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay's webhook: Razorpay tells the server itself that an order was paid, within seconds, so the orders are placed
 * even when the customer's app closed or lost its connection right after paying (the browser never confirms). Set it up
 * in the Razorpay dashboard (Settings > Webhooks): URL {@code https://medizon-mze9.onrender.com/api/payments/razorpay/webhook}, events
 * {@code payment.authorized}, {@code payment.captured} and {@code order.paid}, and the same secret as
 * {@code RAZORPAY_WEBHOOK_SECRET}.
 *
 * <p>Only webhooks signed with that secret are taken; the payment is then looked up with Razorpay's API anyway, so the
 * webhook's contents are never trusted for amounts. Answers 2xx once handled (or not for MediZon), 5xx when Razorpay
 * couldn't be reached, so it sends the webhook again.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RazorpayWebhookApi {

    public static final String PATH = "/api/payments/razorpay/webhook";

    private final RazorpayGateway razorpay;
    private final PaymentService paymentService;
    private final ObjectMapper json;

    @PostMapping(PATH)
    public ResponseEntity<Void> webhook(@RequestBody byte[] body,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        if (!razorpay.takesWebhooks()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        if (!razorpay.isGenuineWebhook(body, signature)) {
            log.warn("Razorpay webhook with a bad signature refused");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        String type = event.path("event").asText("");
        JsonNode payload = event.path("payload");
        String orderId = switch (type) {
            case "payment.authorized", "payment.captured" -> payload.path("payment").path("entity").path("order_id").asText(null);
            case "order.paid" -> payload.path("order").path("entity").path("id").asText(null);
            default -> null;
        };
        if (orderId == null || orderId.isBlank()) return ResponseEntity.ok().build();   // not an event that places orders
        try {
            paymentService.onGatewayPaid(orderId);
        } catch (RuntimeException e) {
            log.warn("Razorpay webhook {} for {} not handled yet: {}", type, orderId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();   // Razorpay sends it again
        }
        return ResponseEntity.ok().build();
    }
}
