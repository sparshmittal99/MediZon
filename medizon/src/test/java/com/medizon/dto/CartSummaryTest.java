package com.medizon.dto;

import com.medizon.domain.RoundOff;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A cart from several stores is rounded the way checkout places it: one order per store, each rounded on its own. */
class CartSummaryTest {

    @Test
    void aSplitCartAddsUpToTheOrdersItBecomes() {
        BigDecimal a = new BigDecimal("100.40"), b = new BigDecimal("44.30"), fee = new BigDecimal("40");
        BigDecimal roundOff = CartSummary.roundOff(List.of(b, a), fee);

        // The fee goes on the bigger order: 140.40 -> 140 and 44.30 -> 44
        BigDecimal orderA = a.add(fee).add(RoundOff.of(a.add(fee)));
        BigDecimal orderB = b.add(RoundOff.of(b));
        assertThat(orderA).isEqualByComparingTo("140");
        assertThat(orderB).isEqualByComparingTo("44");
        assertThat(a.add(b).add(fee).add(roundOff)).isEqualByComparingTo(orderA.add(orderB));
        assertThat(roundOff).isEqualByComparingTo("-0.70");
    }

    @Test
    void aOneStoreCartIsRoundedAsAWhole() {
        assertThat(CartSummary.roundOff(List.of(new BigDecimal("33.50")), new BigDecimal("40"))).isEqualByComparingTo("0.50");
        assertThat(CartSummary.roundOff(List.of(), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }
}
