package com.medizon.payment;

import com.medizon.domain.PaymentMethod;

import java.util.List;
import java.util.Optional;

/**
 * Something the payment page can pay online through. The server opens a payment for the amount, the browser takes it,
 * and the server only trusts it once it has checked it with the gateway itself.
 */
public interface PaymentGateway {

    Gateway id();

    /** Set up and offered. */
    boolean isConfigured();

    /** The methods it takes, in the order the payment page lists them. */
    List<PaymentMethod> methods();

    /** A payment that went through: its id, the gateway order it paid, the amount and how it was paid (null if unknown). */
    record Paid(String id, String orderId, long amountPaise, PaymentMethod method) {}

    /**
     * Opens a payment of {@code paise} and returns the gateway's order id for it.
     *
     * @throws com.medizon.service.BusinessException when the gateway can't be reached or refuses it
     */
    String createOrder(long paise, String reference);

    /**
     * The payment that paid {@code orderId}, checked with the gateway and taken (captured) if it needs to be.
     * {@code paymentId} and {@code signature} are what the browser's checkout handed back, where the gateway gives them.
     *
     * @throws com.medizon.service.BusinessException when it isn't paid, or isn't genuine
     */
    Paid confirm(String orderId, String paymentId, String signature);

    /**
     * The payment that paid {@code orderId}, looked up with the gateway by the server alone (no browser involved) and
     * taken (captured) if it needs to be; empty while nothing has been paid. This is how a payment whose browser
     * closed, or lost its connection, right after paying still becomes an order.
     *
     * @throws com.medizon.service.BusinessException when the gateway can't be reached, so it can be tried again
     */
    Optional<Paid> findPaid(String orderId);

    /** Every successful payment of {@code orderId}. */
    List<Paid> paidPayments(String orderId);

    /**
     * Gives {@code paise} of payment {@code paymentId} of order {@code orderId} back and returns the refund's id.
     *
     * @throws com.medizon.service.BusinessException when the gateway can't be reached or refuses it, so it can be tried again
     */
    String refund(String orderId, String paymentId, long paise, String reason);
}
