package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Getter @Setter @NoArgsConstructor
public class OrderItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Order order;

    @ManyToOne(optional = false)
    private Medicine medicine;

    /** Snapshot of name and price at order time. */
    private String medicineName;

    @Column(precision = 10, scale = 2)
    private BigDecimal unitPrice;

    private int quantity;

    @Column(precision = 10, scale = 2)
    private BigDecimal lineTotal;
}
