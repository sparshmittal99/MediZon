package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Packs of one order line being sent back. */
@Entity
@Getter @Setter @NoArgsConstructor
public class ReturnItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private ReturnRequest request;

    @ManyToOne(optional = false)
    private OrderItem orderItem;

    private int quantity;

    /** Refund for this line: the price paid per pack times the packs returned. */
    @Column(precision = 10, scale = 2)
    private BigDecimal amount;
}
