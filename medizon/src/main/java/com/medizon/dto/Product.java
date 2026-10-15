package com.medizon.dto;

import com.medizon.domain.Medicine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * A catalogue product as the current shopper sees it: the master details, with the price, stock and expiry of the
 * store that would deliver it to them. Pages use it wherever they used to show a medicine.
 */
public class Product {

    private final Medicine medicine;
    private final Offer offer;

    public Product(Medicine medicine, Offer offer) {
        this.medicine = medicine;
        this.offer = offer;
    }

    public Medicine getMedicine() { return medicine; }
    public Offer getOffer() { return offer; }

    // master details
    public Long getId() { return medicine.getId(); }
    public String getName() { return medicine.getName(); }
    public String getGenericName() { return medicine.getGenericName(); }
    public String getManufacturer() { return medicine.getManufacturer(); }
    public String getCategory() { return medicine.getCategory(); }
    public String getDescription() { return medicine.getDescription(); }
    public String getDosageForm() { return medicine.getDosageForm(); }
    public String getStrength() { return medicine.getStrength(); }
    public int getUnitsPerPack() { return medicine.getUnitsPerPack(); }
    public BigDecimal getMrp() { return medicine.getMrp(); }
    public boolean isPrescriptionRequired() { return medicine.isPrescriptionRequired(); }
    public boolean isReturnable() { return medicine.isReturnable(); }
    public String getImageUrl() { return medicine.getImageUrl(); }
    public String getIcon() { return medicine.getIcon(); }
    public String getUnitLabel() { return medicine.getUnitLabel(); }
    public boolean isActive() { return medicine.isActive(); }

    // from the store that would deliver it
    public BigDecimal getPrice() { return offer.price(); }
    public int getStock() { return offer.stock(); }
    public boolean isInStock() { return offer.stock() > 0; }
    public LocalDate getExpiryDate() { return offer.expiryDate(); }
    public boolean isExpired() { return offer.isExpired(); }
    /** No store that serves the shopper lists it. */
    public boolean isUnavailable() { return !offer.isListed(); }
    public String getStoreName() { return offer.store() == null ? null : offer.store().getName(); }

    public int getDiscountPercent() {
        BigDecimal mrp = medicine.getMrp(), price = getPrice();
        if (mrp == null || price == null || mrp.compareTo(price) <= 0) return 0;
        return mrp.subtract(price).multiply(BigDecimal.valueOf(100)).divide(mrp, 0, RoundingMode.HALF_UP).intValue();
    }
}
