package com.medizon.payment;

import com.medizon.domain.PaymentMethod;
import com.medizon.service.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * MediZon's own UPI checkout, in test mode: it plays the part a UPI payment service plays in a real payment, so the
 * payment page can offer UPI like a payments app (pick a UPI app, or enter and verify a UPI ID, then approve with
 * the UPI PIN) without a gateway account. No money moves. {@code UPI_TEST_MODE=false} turns it off, e.g. once
 * Razorpay takes UPI ({@code RAZORPAY_METHODS}).
 *
 * <p>A payment request lasts {@link #REQUEST_TIME}. The customer approves it in the browser, where the UPI PIN is
 * checked; like a real UPI PIN it never reaches the server. A UPI ID whose name starts with "failure" (e.g.
 * {@code failure@okaxis}) has its payments declined, to try the failure path. Requests live in memory: one open
 * when the app restarts simply expires.
 */
@Slf4j
@Component
public class MediZonUpiGateway implements PaymentGateway {

    /** How long a payment request waits for approval, as UPI apps give. */
    public static final Duration REQUEST_TIME = Duration.ofMinutes(5);

    private static final Pattern UPI_ID = Pattern.compile("^[a-zA-Z0-9._-]{2,256}@[a-zA-Z]{2,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** An open payment request, and the payment once approved. */
    private record Request(long paise, Instant openedAt, Paid paid) {}

    private final Map<String, Request> requests = new ConcurrentHashMap<>();

    @Value("${medizon.payment.upi.test-mode:true}")
    private boolean enabled = true;

    @Override
    public Gateway id() { return Gateway.MEDIZON_UPI; }

    @Override
    public boolean isConfigured() { return enabled; }

    @Override
    public List<PaymentMethod> methods() { return List.of(PaymentMethod.UPI); }

    @Override
    public String createOrder(long paise, String reference) {
        String id = "mzupi_" + digits(14);
        requests.put(id, new Request(paise, Instant.now(), null));
        return id;
    }

    /**
     * Checks a UPI ID the way a UPI app does before paying: a valid handle, and the name the bank has for it (in test
     * mode, the customer's own name).
     *
     * @throws BusinessException when it isn't a valid UPI ID
     */
    public String verify(String upiId, String accountName) {
        if (upiId == null || !UPI_ID.matcher(upiId.trim()).matches()) {
            throw new BusinessException("That isn't a valid UPI ID. It looks like name@bank, e.g. 9876543210@ybl.");
        }
        return accountName == null || accountName.isBlank() ? "Verified UPI user" : accountName;
    }

    /**
     * The customer approved request {@code orderId} in their UPI app (with their UPI PIN), from {@code upiId} or, paid
     * from an app picked on the page, null: the payment goes through, and is returned. Approving again gives the same
     * payment.
     *
     * @throws BusinessException when the request expired, isn't known, or the bank declines it
     */
    public Paid approve(String orderId, String upiId) {
        Request r = requests.get(orderId);
        if (r == null) throw new BusinessException("This payment request has expired. Start the payment again.");
        if (r.paid() != null) return r.paid();
        if (Instant.now().isAfter(r.openedAt().plus(REQUEST_TIME))) {
            throw new BusinessException("This payment request expired before it was approved. No money was taken; start the payment again.");
        }
        // Paid from a UPI app picked on the page there's no UPI ID to check, as with a real app handoff
        if (upiId != null && !upiId.isBlank()) verify(upiId, null);
        if (upiId != null && upiId.trim().toLowerCase().startsWith("failure")) {
            throw new BusinessException("Your bank declined the payment. No money was taken; try another UPI ID or way to pay.");
        }
        // A UPI payment's reference is a 12-digit number, the UTR the customer's bank shows
        Paid paid = new Paid(digits(12), orderId, r.paise(), PaymentMethod.UPI);
        requests.put(orderId, new Request(r.paise(), r.openedAt(), paid));
        log.info("MediZon UPI (test) payment {} of {} paise for {} from {}", paid.id(), r.paise(), orderId, upiId);
        return paid;
    }

    @Override
    public Paid confirm(String orderId, String paymentId, String signature) {
        Request r = requests.get(orderId);
        if (r == null || r.paid() == null) {
            throw new BusinessException("Your UPI payment hasn't been approved, so no order was placed. Please try again.");
        }
        return r.paid();
    }

    @Override
    public List<Paid> paidPayments(String orderId) {
        Request r = requests.get(orderId);
        return r == null || r.paid() == null ? List.of() : List.of(r.paid());
    }

    /** In test mode a refund always goes through: nothing was taken, so nothing has to move. */
    @Override
    public String refund(String orderId, String paymentId, long paise, String reason) {
        String id = "rfnd_upi_" + digits(10);
        log.info("MediZon UPI (test) refund {} of {} paise of {} ({})", id, paise, paymentId, reason);
        return id;
    }

    private static String digits(int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(RANDOM.nextInt(10));
        return b.toString();
    }
}
