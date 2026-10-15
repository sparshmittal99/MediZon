package com.medizon.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Bills are rounded to the nearest rupee, as on printed invoices. The paise added or taken off are shown as a
 * separate "Round off" line and booked to their own ledger in the reports, so the books still balance.
 */
public final class RoundOff {

    private RoundOff() {}

    /** What to add to {@code amount} to reach the nearest whole rupee: between -0.49 and +0.50. */
    public static BigDecimal of(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).subtract(amount).setScale(2, RoundingMode.HALF_UP);
    }

    /** "+₹0.40" or "−₹0.20", as printed on bills. */
    public static String label(BigDecimal roundOff) {
        BigDecimal v = (roundOff == null ? BigDecimal.ZERO : roundOff).setScale(2, RoundingMode.HALF_UP);
        return (v.signum() < 0 ? "−₹" : "+₹") + v.abs().toPlainString();
    }
}
