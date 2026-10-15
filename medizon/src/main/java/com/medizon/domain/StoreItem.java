package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A product from the master catalogue as one store sells it: its stock and batch expiry, and for OTC medicines its own
 * selling price (prescription medicines sell at the master price in every store).
 */
@Entity
@Table(name = "store_items", uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "medicine_id"}))
@Getter @Setter @NoArgsConstructor
public class StoreItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Store store;

    @ManyToOne(optional = false)
    private Medicine medicine;

    /** The store's selling price for an OTC medicine; empty means the master price. Ignored for prescription medicines. */
    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    /** Packs in this store. */
    private int stock;

    /** Expiry of the batch in stock. Expired stock can't be sold. */
    private LocalDate expiryDate;

    /** Listed in this store's catalogue. */
    private boolean active = true;

    /** What this store charges per pack. */
    public BigDecimal getSellingPrice() {
        if (medicine.isPrescriptionRequired() || price == null) return medicine.getPrice();
        return price;
    }

    public boolean isExpired() { return expiryDate != null && expiryDate.isBefore(LocalDate.now()); }

    /** Expires within the next 90 days: sell it first or pull it. */
    public boolean isExpiringSoon() {
        return expiryDate != null && !isExpired() && expiryDate.isBefore(LocalDate.now().plusDays(90));
    }

    /** Listed, in stock and in date, at a store that's open. */
    public boolean isSellable() {
        return active && store.isActive() && medicine.isActive() && stock > 0 && !isExpired();
    }
}
