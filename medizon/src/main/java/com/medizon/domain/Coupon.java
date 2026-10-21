package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A promo code taken off the medicines in an order (not the delivery fee). A customer can use each code once a day.
 * Seeded with a few sample promos for now; the admin will manage them later.
 */
@Entity
@Table(name = "coupons")
@Getter @Setter @NoArgsConstructor
public class Coupon {

    public enum Kind { PERCENT, FLAT }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What the customer types, stored in capitals. */
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    /** "10% off up to ₹100", shown on the promo card. */
    @Column(nullable = false, length = 80)
    private String title;

    @Column(length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind = Kind.PERCENT;

    /** Percent off, or rupees off for a flat promo. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** Cap on a percent promo; null for no cap. */
    @Column(precision = 10, scale = 2)
    private BigDecimal maxDiscount;

    /** Medicines must add up to at least this much. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal minOrder = BigDecimal.ZERO;

    private boolean active = true;

    /** Last day it can be used; null for no end. */
    private LocalDate validUntil;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Coupon(String code, String title, String description, Kind kind, String amount, String maxDiscount, String minOrder) {
        this.code = code;
        this.title = title;
        this.description = description;
        this.kind = kind;
        this.amount = new BigDecimal(amount);
        this.maxDiscount = maxDiscount == null ? null : new BigDecimal(maxDiscount);
        this.minOrder = new BigDecimal(minOrder);
    }

    public boolean isLive() {
        return active && (validUntil == null || !LocalDate.now().isAfter(validUntil));
    }

    /** Rupees off medicines worth {@code subtotal}: never more than the medicines cost. Zero below the minimum. */
    public BigDecimal discountOn(BigDecimal subtotal) {
        if (subtotal.compareTo(minOrder) < 0) return BigDecimal.ZERO.setScale(2);
        BigDecimal off = kind == Kind.FLAT ? amount
                : subtotal.multiply(amount).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (maxDiscount != null) off = off.min(maxDiscount);
        return off.min(subtotal).setScale(2, RoundingMode.HALF_UP);
    }
}
