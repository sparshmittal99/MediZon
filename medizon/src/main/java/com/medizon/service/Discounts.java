package com.medizon.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Spreads a promo discount over cart lines (and so over the orders a cart splits into), in proportion to what each
 * line costs. Cart and checkout split the same way, so the cart's total is what the orders add up to.
 */
public final class Discounts {

    private Discounts() {}

    /** {@code amount} split over {@code weights} to the paisa; the biggest weight takes what rounding leaves over. */
    public static List<BigDecimal> split(BigDecimal amount, List<BigDecimal> weights) {
        List<BigDecimal> out = new ArrayList<>();
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (amount == null || amount.signum() <= 0 || sum.signum() <= 0) {
            weights.forEach(w -> out.add(zero));
            return out;
        }
        int biggest = 0;
        BigDecimal given = BigDecimal.ZERO;
        for (int k = 0; k < weights.size(); k++) {
            BigDecimal share = amount.multiply(weights.get(k)).divide(sum, 2, RoundingMode.DOWN);
            out.add(share);
            given = given.add(share);
            if (weights.get(k).compareTo(weights.get(biggest)) > 0) biggest = k;
        }
        out.set(biggest, out.get(biggest).add(amount.setScale(2, RoundingMode.HALF_UP).subtract(given)));
        return out;
    }
}
