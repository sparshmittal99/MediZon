package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * How customers order, set by the admin. One row. For now the smallest cart that can be checked out: the medicines'
 * total before any promo or delivery fee must reach it. Zero means any amount.
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

    private LocalDateTime updatedAt;

    @Column(length = 120)
    private String updatedBy;

    /** The settings before the admin has changed anything. */
    public static OrderSettings defaults() { return new OrderSettings(); }
}
