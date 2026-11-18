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
    public static final BigDecimal DEFAULT_FREE_DELIVERY_ABOVE = new BigDecimal("499");
    public static final BigDecimal DEFAULT_DELIVERY_FEE = new BigDecimal("40");

    @Id
    private Long id = ID;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal minOrderValue = DEFAULT_MIN_ORDER_VALUE;

    /** Carts whose medicines come to this much or more ship free; zero makes every delivery free. */
    @Column(nullable = false, precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 499")
    private BigDecimal freeDeliveryAbove = DEFAULT_FREE_DELIVERY_ABOVE;

    /** What smaller carts pay for delivery; zero makes every delivery free. */
    @Column(nullable = false, precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 40")
    private BigDecimal deliveryFee = DEFAULT_DELIVERY_FEE;

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

    /** Delivery charge for medicines worth {@code subtotal}: free for an empty cart, at or above the threshold, or when the fee is zero. */
    public BigDecimal deliveryFeeFor(BigDecimal subtotal) {
        BigDecimal fee = deliveryFee == null ? DEFAULT_DELIVERY_FEE : deliveryFee;
        BigDecimal above = freeDeliveryAbove == null ? DEFAULT_FREE_DELIVERY_ABOVE : freeDeliveryAbove;
        if (subtotal == null || subtotal.signum() == 0 || fee.signum() <= 0) return BigDecimal.ZERO;
        if (above.signum() <= 0 || subtotal.compareTo(above) >= 0) return BigDecimal.ZERO;
        return fee;
    }

    /** Every delivery is free, whatever the cart's value. */
    public boolean isDeliveryAlwaysFree() {
        return (deliveryFee != null && deliveryFee.signum() <= 0) || (freeDeliveryAbove != null && freeDeliveryAbove.signum() <= 0);
    }

    /** The settings before the admin has changed anything. */
    public static OrderSettings defaults() { return new OrderSettings(); }
}
