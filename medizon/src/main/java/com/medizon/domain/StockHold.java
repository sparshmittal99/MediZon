package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Packs of a store's stock set aside for a customer while they pay online, so nobody else can buy them meanwhile. A hold
 * only lowers what's available to others (stock less active holds); the stock itself changes only when the order is
 * placed. It ends when the order is placed, the payment is refunded or closed, or at {@link #expiresAt}, whichever is
 * first: an expired hold no longer counts, even before it's deleted.
 */
@Entity
@Table(name = "stock_holds", indexes = {
        @Index(columnList = "storeItemId, expiresAt"),
        @Index(columnList = "paymentId"),
        @Index(columnList = "userId")})
@Getter @Setter @NoArgsConstructor
public class StockHold {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeItemId;

    /** The customer paying; their own holds never count against them. */
    @Column(nullable = false)
    private Long userId;

    /** The {@link CheckoutPayment} the packs are held for. */
    @Column(nullable = false)
    private Long paymentId;

    private int quantity;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt = LocalDateTime.now();

    public StockHold(Long storeItemId, Long userId, Long paymentId, int quantity, LocalDateTime expiresAt) {
        this.storeItemId = storeItemId;
        this.userId = userId;
        this.paymentId = paymentId;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
    }
}
