package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * "Notify me" on an out-of-stock product: the customer is told once it's back in stock for the place they were
 * shopping for, and the alert is then removed.
 */
@Entity
@Table(name = "stock_alerts", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "medicine_id"}))
@Getter @Setter @NoArgsConstructor
public class StockAlert {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @ManyToOne(optional = false)
    private Medicine medicine;

    /** The delivery PIN code the customer was shopping for; null means any open store. */
    @Column(length = 6)
    private String pincode;

    private LocalDateTime createdAt = LocalDateTime.now();
}
