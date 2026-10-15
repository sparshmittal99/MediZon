package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A store delivering to a PIN code, and how long it takes there (0 to 0 days is same-day). When several stores serve
 * a PIN code, the quickest one is the nearest: it delivers whatever it has in stock, and the next quickest delivers
 * what it doesn't.
 */
@Entity
@Table(name = "store_areas", uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "pincode"}))
@Getter @Setter @NoArgsConstructor
public class StoreArea {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Store store;

    @Column(nullable = false, length = 6)
    private String pincode;

    private int minDays;

    private int maxDays;

    public String getDaysLabel() {
        if (maxDays == 0) return "Same day";
        if (minDays == maxDays) return minDays + (minDays == 1 ? " day" : " days");
        return minDays + "–" + maxDays + " days";
    }
}
