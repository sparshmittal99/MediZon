package com.medizon.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.medizon.domain.PaymentMethod;
import com.medizon.service.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Razorpay (razorpay.com). Set {@code RAZORPAY_KEY_ID} and {@code RAZORPAY_KEY_SECRET}; test-mode keys
 * ({@code rzp_test_...}) take test payments and no money moves. {@code RAZORPAY_METHODS} lists what the account takes:
 * cards and net banking by default, since Razorpay only turns UPI on after KYC.
 *
 * <p>Razorpay Checkout hands back a signature of "order id|payment id" made with the key secret; the payment is only
 * trusted once it checks out, and taken (captured) if the account doesn't capture by itself. Refunds go back to the
 * card or account that paid.
 */
@Slf4j
@Component
public class RazorpayGateway implements PaymentGateway {

    private final String keyId;
    private final String keySecret;
    private final RestClient http;

    @Value("${medizon.payment.razorpay.methods:CARD,NETBANKING}")
    private String methodList;

    public RazorpayGateway(@Value("${medizon.payment.razorpay.key-id:}") String keyId,
                           @Value("${medizon.payment.razorpay.key-secret:}") String keySecret,
                           @Value("${medizon.payment.razorpay.url:https://api.razorpay.com/v1}") String baseUrl) {
        this.keyId = keyId.trim();
        this.keySecret = keySecret.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(this.keyId, this.keySecret)).build();
    }

    @Override
    public Gateway id() { return Gateway.RAZORPAY; }

    /** Has both keys. */
    @Override
    public boolean isConfigured() { return !keyId.isEmpty() && !keySecret.isEmpty(); }

    @Override
    public List<PaymentMethod> methods() { return methods(methodList == null ? "CARD,NETBANKING" : methodList); }

    /** "UPI, CARD" as methods, in the payment page's order; anything that isn't an online method is left out. */
    static List<PaymentMethod> methods(String list) {
        List<String> names = Arrays.stream(list.split(",")).map(s -> s.trim().toUpperCase()).toList();
        return Arrays.stream(PaymentMethod.online()).filter(m -> names.contains(m.name())).toList();
    }

    /** The public key the browser's checkout opens with. */
    public String getKeyId() { return keyId; }

    /** Razorpay Checkout's name for a method. */
    public static String checkoutMethod(PaymentMethod method) {
        return switch (method) {
            case UPI -> "upi";
            case NETBANKING -> "netbanking";
            default -> "card";
        };
    }

    @Override
    public String createOrder(long paise, String reference) {
        try {
            JsonNode body = http.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", paise, "currency", "INR", "receipt", reference))
                    .retrieve().body(JsonNode.class);
            String id = body == null ? null : body.path("id").asText(null);
            if (id == null) throw new RestClientException("no order id in Razorpay's reply");
            return id;
        } catch (RestClientException e) {
            log.warn("Razorpay order for {} failed: {}", reference, e.getMessage());
            throw new BusinessException("Online payment isn't available right now. Try again in a few minutes, or pay on delivery.");
        }
    }

    @Override
    public Paid confirm(String orderId, String paymentId, String signature) {
        if (!isGenuine(orderId, paymentId, signature)) {
            log.warn("Payment {} for {} has a bad signature", paymentId, orderId);
            throw new BusinessException("We couldn't confirm that payment. If money left your account, it's returned automatically.");
        }
        JsonNode p = get("/payments/{id}", paymentId);
        if (!orderId.equals(p.path("order_id").asText(null))) throw new BusinessException("That payment doesn't match this order.");
        String status = p.path("status").asText();
        if ("authorized".equals(status)) {
            capture(paymentId, p.path("amount").asLong());
        } else if (!"captured".equals(status)) {
            throw new BusinessException("Your payment hasn't gone through, so no order was placed. Please try again.");
        }
        return paid(p);
    }

    @Override
    public List<Paid> paidPayments(String orderId) {
        List<Paid> out = new ArrayList<>();
        get("/orders/{id}/payments", orderId).path("items").forEach(p -> {
            // Authorized but never captured payments are released by Razorpay and the bank on their own
            if ("captured".equals(p.path("status").asText())) out.add(paid(p));
        });
        return out;
    }

    @Override
    public String refund(String orderId, String paymentId, long paise, String reason) {
        try {
            JsonNode body = http.post().uri("/payments/{id}/refund", paymentId).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", paise, "notes", Map.of("reason", reason))).retrieve().body(JsonNode.class);
            String id = body == null ? null : body.path("id").asText(null);
            if (id == null) throw new RestClientException("no refund id in Razorpay's reply");
            log.info("Refunded {} paise of {} ({}): {}", paise, paymentId, reason, id);
            return id;
        } catch (RestClientException e) {
            log.warn("Razorpay refund of {} paise of {} failed: {}", paise, paymentId, e.getMessage());
            throw new BusinessException("The refund couldn't be sent to Razorpay right now. Try again in a few minutes.");
        }
    }

    /** Takes an authorized payment; accounts set to capture automatically never need it. */
    private void capture(String paymentId, long paise) {
        try {
            http.post().uri("/payments/{id}/capture", paymentId).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", paise, "currency", "INR")).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Razorpay capture of {} failed: {}", paymentId, e.getMessage());
            throw new BusinessException("We couldn't confirm your payment. If money left your account, it's returned automatically.");
        }
    }

    private JsonNode get(String uri, String id) {
        try {
            JsonNode body = http.get().uri(uri, id).retrieve().body(JsonNode.class);
            if (body == null) throw new RestClientException("empty reply");
            return body;
        } catch (RestClientException e) {
            log.warn("Razorpay lookup {} {} failed: {}", uri, id, e.getMessage());
            throw new BusinessException("We couldn't reach Razorpay to confirm your payment. Try again in a minute.");
        }
    }

    private static Paid paid(JsonNode p) {
        PaymentMethod method = switch (p.path("method").asText("")) {
            case "upi" -> PaymentMethod.UPI;
            case "card" -> PaymentMethod.CARD;
            case "netbanking" -> PaymentMethod.NETBANKING;
            default -> null;
        };
        return new Paid(p.path("id").asText(), p.path("order_id").asText(null), p.path("amount").asLong(), method);
    }

    /** Razorpay signs "order id|payment id" with the key secret (HMAC-SHA256, hex); anything else is forged. */
    public boolean isGenuine(String orderId, String paymentId, String signature) {
        if (!isConfigured() || orderId == null || paymentId == null || signature == null) return false;
        return MessageDigest.isEqual(sign(orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
