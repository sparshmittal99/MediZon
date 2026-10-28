package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An online payment for a cart, before any order exists: what the customer chose on the payment page, so the orders
 * are placed exactly as chosen once the gateway confirms the money. A payment that can't become orders is refunded.
 */
@Entity
@Table(name = "checkout_payments", indexes = @Index(columnList = "status, createdAt"))
@Getter @Setter @NoArgsConstructor
public class CheckoutPayment {

    public enum Status {
        /** Gateway order created; the customer is paying. */
        CREATED,
        /** A confirmation is placing the orders right now, so a second one doesn't place them twice. */
        PLACING,
        PLACED,
        /** Paid, but the orders couldn't be placed: the money went back. */
        REFUNDED,
        /** Never paid. */
        EXPIRED
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    /** Null on payments from before there were several gateways, which all went through Razorpay. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private com.medizon.payment.Gateway gateway;

    @Column(nullable = false, unique = true, length = 45)
    private String gatewayOrderId;

    @Column(length = 40)
    private String gatewayPaymentId;

    @Column(length = 40)
    private String gatewayRefundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    private Long addressId;

    @Column(length = 20)
    private String couponCode;

    private boolean useWallet;

    /** What the gateway takes: the cart's total less the MediZon Money it uses. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.CREATED;

    /** The order to open once placed (the first one when the cart became several). */
    private Long firstOrderId;
    private int orderCount;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
