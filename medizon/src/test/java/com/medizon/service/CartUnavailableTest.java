package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.CartSummary;
import com.medizon.dto.Offer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Ticked items that can't be ordered right now: checkout waits, and says why in the cart's own words. */
class CartUnavailableTest {

    @Test
    void anOutOfStockItemIsCalledOutOfStockNotUndeliverable() {
        assertThat(OrderService.unavailableReason(List.of(line("Acitrom 3", 0, 1)), "400050"))
                .isEqualTo("Acitrom 3 is out of stock right now. Untick or remove it to check out.");
    }

    @Test
    void eachKindOfProblemIsNamed() {
        String why = OrderService.unavailableReason(List.of(line("Dolo 650", 2, 5), line("Acitrom 3", 0, 1),
                notSold("Azithral 500")), "400050");
        assertThat(why).isEqualTo("Acitrom 3 is out of stock right now. Only 2 packs of Dolo 650 are available right now."
                + " Azithral 500 isn't sold for delivery to 400050. Untick or remove them to check out, or choose another address.");
    }

    @Test
    void checkoutWaitsWhileATickedItemCantBeOrdered() {
        CartItem ok = line("Dolo 650", 10, 1), out = line("Acitrom 3", 0, 1);
        CartSummary cart = new CartSummary(List.of(ok, out), BigDecimal.TEN, BigDecimal.ZERO, false);
        assertThat(cart.getSelectedCount()).isEqualTo(2);
        assertThat(cart.getBlockedCount()).isEqualTo(1);

        out.setSelected(false);                                  // unticked: checkout can go ahead with the rest
        assertThat(cart.getBlockedCount()).isZero();
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
