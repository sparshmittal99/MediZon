package com.medizon.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A product in the master catalogue, the same for every store: what it is, its MRP, tax details and the master price.
 * Prescription medicines sell at the master price everywhere; OTC medicines at each store's own price when it sets one.
 * Stock and batch expiry belong to each store's listing ({@link StoreItem}).
 */
@Entity
@Getter @Setter @NoArgsConstructor
public class Medicine {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Internal product code for the admin and stores ("MZP00042"), unique and given automatically when the product is
     * added. Customers never see it.
     */
    @Column(unique = true, length = 16)
    private String productCode;

    @NotBlank(message = "Enter the medicine name")
    @Column(nullable = false)
    private String name;

    /** Salt / composition, e.g. "Paracetamol 650 mg". */
    private String genericName;

    private String manufacturer;

    @NotBlank(message = "Choose a category")
    @Column(nullable = false)
    private String category;

    @Column(length = 2000)
    private String description;

    /** Tablet, Capsule, Syrup, Sachet, Device ... */
    @NotBlank(message = "Enter the dosage form")
    private String dosageForm = "Tablet";

    private String strength;

    /**
     * What one pack holds: a count of tablets or capsules ("10"), or a quantity with its unit ("100 ml", "30 g").
     * Its number ({@link #getUnitCount()}) converts ordered packs into units at home.
     */
    @NotBlank(message = "Enter the units per pack")
    @Size(max = 20, message = "Keep units per pack to 20 characters")
    @Pattern(regexp = "\\d*[1-9]\\d*(\\.\\d+)?\\s*[A-Za-z]*\\.?",
            message = "Units per pack is a number, with a unit if needed: 10, 100 ml or 30 g")
    @Column(length = 20)
    private String unitsPerPack = "10";

    /** Selling price for prescription medicines in every store, and the default for OTC ones. */
    @NotNull(message = "Enter a price")
    @DecimalMin(value = "0.0", message = "Price can't be negative")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(precision = 10, scale = 2)
    private BigDecimal mrp;

    private boolean prescriptionRequired;

    /** HSN code printed on invoices; 3004 covers packaged medicaments. */
    @NotBlank(message = "Enter the HSN code")
    @Pattern(regexp = "\\d{4,8}", message = "HSN codes are 4 to 8 digits")
    @Column(length = 8, columnDefinition = "varchar(8) not null default '3004'")
    private String hsnCode = "3004";

    /** GST rate in percent. Prices are GST-inclusive; the invoice works out the tax inside the price. */
    @NotNull(message = "Enter the GST rate")
    @DecimalMin(value = "0.0", message = "GST can't be negative")
    @DecimalMax(value = "40.0", message = "GST can't be above 40%")
    // A column default, not columnDefinition: Hibernate's schema update put "not null default" inside "set data type"
    @Column(nullable = false, precision = 5, scale = 2)
    @ColumnDefault("5")
    private BigDecimal gstRate = new BigDecimal("5");

    private boolean active = true;

    /** Can customers send it back after delivery? Admins set it; the product page shows it. */
    @Column(columnDefinition = "boolean not null default true")
    private boolean returnable = true;

    private String imageUrl;

    public int getDiscountPercent() {
        if (mrp == null || price == null || mrp.compareTo(price) <= 0) return 0;
        return mrp.subtract(price).multiply(BigDecimal.valueOf(100))
                .divide(mrp, 0, RoundingMode.HALF_UP).intValue();
    }

    public String getIcon() {
        String f = dosageForm == null ? "" : dosageForm.toLowerCase();
        if (f.contains("syrup") || f.contains("drop") || f.contains("liquid")) return "bi-droplet-half";
        if (f.contains("injection")) return "bi-eyedropper";
        if (f.contains("device") || f.contains("strip")) return "bi-heart-pulse";
        if (f.contains("sachet") || f.contains("powder")) return "bi-cup-hot";
        if (f.contains("capsule")) return "bi-capsule";
        return "bi-capsule-pill";
    }

    /** Word used for one unit of this medicine in reminders: "tablet(s)", "ml" ... */
    public String getUnitLabel() {
        String f = dosageForm == null ? "unit" : dosageForm.toLowerCase();
        if (f.contains("syrup") || f.contains("liquid")) return "ml";
        if (f.contains("drop")) return "drop(s)";
        if (f.contains("device") || f.contains("strip")) return "unit(s)";
        return f + "(s)";
    }

    /** Spaces trimmed and squeezed, so "100  ml " is stored as "100 ml". */
    public void setUnitsPerPack(String unitsPerPack) {
        this.unitsPerPack = unitsPerPack == null ? null : unitsPerPack.trim().replaceAll("\\s+", " ");
    }

    /** The number in {@link #unitsPerPack}: 10 for "10", 100 for "100 ml"; 1 when there is none. */
    public int getUnitCount() {
        java.util.regex.Matcher n = java.util.regex.Pattern.compile("\\d+").matcher(unitsPerPack == null ? "" : unitsPerPack);
        if (!n.find()) return 1;
        try {
            return Math.max(1, Integer.parseInt(n.group()));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** What a pack holds, for shoppers: "10 tablet(s)" for a plain count, "100 ml" or "30 g" as given otherwise. */
    public String getPackLabel() {
        if (unitsPerPack == null || unitsPerPack.isBlank()) return getUnitCount() + " " + getUnitLabel();
        return unitsPerPack.matches("[\\d.]+") ? unitsPerPack + " " + getUnitLabel() : unitsPerPack;
    }

    /** Above MRP is never allowed: a store's OTC price, or the master price. */
    public boolean exceedsMrp(BigDecimal amount) {
        return amount != null && mrp != null && mrp.signum() > 0 && amount.compareTo(mrp) > 0;
    }
}
