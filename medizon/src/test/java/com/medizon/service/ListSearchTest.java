package com.medizon.service;

import com.medizon.domain.Coupon;
import com.medizon.domain.Medicine;
import com.medizon.domain.SupportTicket;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Searches and orderings on the staff lists and at checkout. */
class ListSearchTest {

    private static Coupon promo(String code, Coupon.Kind kind, String amount, String min) {
        Coupon c = new Coupon();
        c.setCode(code);
        c.setKind(kind);
        c.setAmount(new BigDecimal(amount));
        c.setMinOrder(new BigDecimal(min));
        c.setUsesPerDay(1);
        c.setActive(true);
        return c;
    }

    @Test
    void checkoutPromosShowTheBiggestSavingFirst() {
        Coupon flat50 = promo("FLAT50", Coupon.Kind.FLAT, "50", "0");
        Coupon pct10 = promo("TEN", Coupon.Kind.PERCENT, "10", "0");        // ₹80 off ₹800
        Coupon big = promo("BIG200", Coupon.Kind.FLAT, "200", "1000");      // needs ₹1,000
        Coupon used = promo("USED100", Coupon.Kind.FLAT, "100", "0");       // used up today
        List<Coupon> sorted = CouponService.bestFirst(List.of(flat50, big, used, pct10), new BigDecimal("800"), Set.of("USED100"));
        assertThat(sorted).extracting(Coupon::getCode).containsExactly("TEN", "FLAT50", "BIG200", "USED100");
    }

    @Test
    void ticketNumbersMatchWithOrWithoutPrefixAndZeros() {
        SupportTicket t = new SupportTicket();
        t.setId(42L);
        assertThat(SupportService.numberMatches(t, "TKT-000042")).isTrue();
        assertThat(SupportService.numberMatches(t, "tkt-42")).isTrue();
        assertThat(SupportService.numberMatches(t, " 42 ")).isTrue();
        assertThat(SupportService.numberMatches(t, "0004")).isTrue();
        assertThat(SupportService.numberMatches(t, "43")).isFalse();
        assertThat(SupportService.numberMatches(t, "")).isTrue();
    }

    @Test
    void productsAreFoundByNameOrCode() {
        Medicine m = new Medicine();
        m.setId(12L);
        m.setName("Dolo 650");
        m.setProductCode(MedicineService.productCode(12L));
        assertThat(m.getProductCode()).isEqualTo("MZP00012");
        assertThat(MedicineService.matches(m, "dolo")).isTrue();
        assertThat(MedicineService.matches(m, "mzp00012")).isTrue();
        assertThat(MedicineService.matches(m, "00012")).isTrue();
        assertThat(MedicineService.matches(m, "crocin")).isFalse();
        assertThat(MedicineService.matches(m, null)).isTrue();
    }
}
