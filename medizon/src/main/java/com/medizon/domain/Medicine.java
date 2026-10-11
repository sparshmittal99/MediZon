package com.medizon.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Entity
@Getter @Setter @NoArgsConstructor
public class Medicine {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    /** Tablets/capsules/ml per pack. Used to convert ordered packs into units at home. */
    @Min(value = 1, message = "A pack holds at least 1 unit")
    private int unitsPerPack = 10;

    @NotNull(message = "Enter a price")
    @DecimalMin(value = "0.0", message = "Price can't be negative")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(precision = 10, scale = 2)
    private BigDecimal mrp;

    /** Packs in the warehouse. */
    @Min(value = 0, message = "Stock can't be negative")
    private int stock;

    private boolean prescriptionRequired;

    private boolean active = true;

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

    public boolean isInStock() { return stock > 0; }
}
