package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.dto.Product;
import com.medizon.repository.MedicineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * Other brands of the same medicine: the same ingredients at the same strengths in the same dosage form, sold to the
 * shopper's location. Packs differ in size, so they're compared by the price of one unit (a tablet, a ml ...).
 */
@Service
@RequiredArgsConstructor
public class SubstituteService {

    public static final int MAX = 5;

    private final MedicineRepository medicines;
    private final CatalogService catalog;

    /**
     * One other brand, with its price per unit and how much cheaper (positive) or dearer (negative) it is than the
     * medicine being looked at, in percent. {@code savingPerPack}: what a pack-size of the original would cost less.
     */
    public record Substitute(Product product, BigDecimal unitPrice, int savingPercent, BigDecimal savingPerPack) {
        public boolean isCheaper() { return savingPercent > 0; }
    }

    /** What the medicine itself costs per unit, and its other brands: in stock first, then cheapest first. */
    public record Result(Product original, BigDecimal unitPrice, List<Substitute> substitutes) {
        public boolean isEmpty() { return substitutes.isEmpty(); }

        public Substitute cheapest() {
            return substitutes.stream().filter(s -> s.product().isInStock() && s.isCheaper())
                    .max(Comparator.comparingInt(Substitute::savingPercent)).orElse(null);
        }
    }

    @Transactional(readOnly = true)
    public Result find(Medicine m, String pincode) {
        Product original = catalog.product(m, pincode);
        BigDecimal unit = unitPrice(original);
        Composition wanted = Composition.of(m);
        if (!wanted.isComparable()) return new Result(original, unit, List.of());

        List<Medicine> same = medicines.findByActiveTrueOrderByNameAsc().stream()
                .filter(o -> !o.getId().equals(m.getId()))
                .filter(o -> wanted.sameAs(Composition.of(o)))
                .toList();
        List<Substitute> out = catalog.products(same, pincode).stream()
                .filter(p -> !p.isUnavailable() && p.getPrice() != null)
                .map(p -> substitute(p, unit, original.getUnitCount()))
                .sorted(Comparator.comparing((Substitute s) -> !s.product().isInStock())
                        .thenComparing(Substitute::unitPrice))
                .limit(MAX)
                .toList();
        return new Result(original, unit, out);
    }

    private static Substitute substitute(Product p, BigDecimal originalUnit, int originalUnits) {
        BigDecimal unit = unitPrice(p);
        if (originalUnit == null || originalUnit.signum() == 0) return new Substitute(p, unit, 0, BigDecimal.ZERO);
        BigDecimal diff = originalUnit.subtract(unit);
        int percent = diff.multiply(BigDecimal.valueOf(100)).divide(originalUnit, 0, RoundingMode.HALF_UP).intValue();
        return new Substitute(p, unit, percent, diff.multiply(BigDecimal.valueOf(originalUnits)).setScale(2, RoundingMode.HALF_UP));
    }

    static BigDecimal unitPrice(Product p) {
        if (p.getPrice() == null) return null;
        return p.getPrice().divide(BigDecimal.valueOf(Math.max(1, p.getUnitCount())), 2, RoundingMode.HALF_UP);
    }
}
