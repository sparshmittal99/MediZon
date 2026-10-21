package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One movement of MediZon Money: a refund or cancellation paid in, or an order paid with it. */
@Entity
@Table(name = "wallet_transactions", indexes = @Index(columnList = "user_id, createdAt"))
@Getter @Setter @NoArgsConstructor
public class WalletTransaction {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    /** Positive when money comes in, negative when it's spent. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** Balance right after this movement. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false, length = 200)
    private String description;

    /** Where the customer can see more, e.g. the order page. */
    @Column(length = 200)
    private String link;

    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isCredit() { return amount.signum() > 0; }
}
