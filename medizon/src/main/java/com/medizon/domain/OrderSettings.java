package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * How customers order, set by the admin. One row: the smallest cart that can be checked out (the medicines' total
 * before any promo or delivery fee must reach it; zero means any amount), and what each way to pay costs.
 */
@Entity
@Table(name = "order_settings")
@Getter @Setter @NoArgsConstructor
public class OrderSettings {

    public static final long ID = 1L;
    public static final BigDecimal DEFAULT_MIN_ORDER_VALUE = new BigDecimal("99");

    @Id
    private Long id = ID;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal minOrderValue = DEFAULT_MIN_ORDER_VALUE;

    /*
     * Charges for paying a certain way, before GST (shown as "₹10 + GST"); zero means free. Added to what's paid that
     * way at checkout, with GST at {@link #paymentFeeGstRate}.
     */
    @Column(nullable = false, precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 0")
    private BigDecimal upiFee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 10")
    private BigDecimal cardFee = new BigDecimal("10.00");

    @Column(nullable = false, precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 10")
    private BigDecimal netbankingFee = new BigDecimal("10.00");

    @Column(nullable = false, precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 0")
    private BigDecimal codFee = BigDecimal.ZERO;

    /** GST on the payment charges, in percent. */
    @Column(nullable = false, precision = 5, scale = 2, columnDefinition = "numeric(5,2) not null default 18")
    private BigDecimal paymentFeeGstRate = new BigDecimal("18.00");

    private LocalDateTime updatedAt;

    @Column(length = 120)
    private String updatedBy;

    /** The settings before the admin has changed anything. */
    public static OrderSettings defaults() { return new OrderSettings(); }
}
