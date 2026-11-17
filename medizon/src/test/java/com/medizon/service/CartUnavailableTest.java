package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.CartSummary;
import com.medizon.dto.Offer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Lines that can't be ordered right now stay in the cart, listed first; checkout goes ahead with the rest. */
class CartUnavailableTest {

    @Test
    void checkoutTakesTheRestAndLeavesUnavailableLinesInTheCart() {
        CartItem ok = line("Dolo 650", 10, 1), out = line("Acitrom 3", 0, 1), few = line("Pan 40", 2, 5);
        CartSummary cart = new CartSummary(List.of(ok, out, few), new BigDecimal("30.00"), BigDecimal.ZERO, false);

        assertThat(cart.getSelectedCount()).isEqualTo(3);
        assertThat(cart.getCheckoutCount()).isEqualTo(1);
        assertThat(cart.getOrderable()).containsExactly(ok);
        assertThat(cart.getUnavailable()).containsExactly(out, few);
    }

    @Test
    void unavailableLinesAreListedFirst() {
        CartItem ok = line("Dolo 650", 10, 1), out = line("Acitrom 3", 0, 1), notSold = notSold("Azithral 500");
        CartSummary cart = new CartSummary(List.of(ok, out, notSold), new BigDecimal("30.00"), BigDecimal.ZERO, false);

        assertThat(cart.getDisplayItems()).containsExactly(out, notSold, ok);
    }

    @Test
    void anUntickedLineIsNotOrdered() {
        CartItem ok = line("Dolo 650", 10, 1), later = line("Pan 40", 10, 1);
        later.setSelected(false);
        CartSummary cart = new CartSummary(List.of(ok, later), new BigDecimal("30.00"), BigDecimal.ZERO, false);

        assertThat(cart.getOrderable()).containsExactly(ok);
        assertThat(cart.getAvailable()).containsExactly(ok, later);
    }

    private static CartItem line(String name, int stock, int qty) {
        Medicine m = medicine(name);
        Store s = new Store();
        s.setId(1L);
        StoreItem listing = new StoreItem();
        listing.setStore(s);
        listing.setMedicine(m);
        listing.setStock(stock);
        CartItem i = item(m, qty);
        i.setOffer(new Offer(m, listing, null, stock));
        return i;
    }

    private static CartItem notSold(String name) {
        Medicine m = medicine(name);
        CartItem i = item(m, 1);
        i.setOffer(Offer.none(m));
        return i;
    }

    private static CartItem item(Medicine m, int qty) {
        CartItem i = new CartItem();
        i.setMedicine(m);
        i.setQuantity(qty);
        i.setSelected(true);
        return i;
    }

    private static Medicine medicine(String name) {
        Medicine m = new Medicine();
        m.setId((long) name.hashCode());
        m.setName(name);
        m.setPrice(new BigDecimal("30.00"));
        return m;
    }
}
