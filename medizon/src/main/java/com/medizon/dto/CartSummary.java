package com.medizon.dto;

import com.medizon.domain.CartItem;
import com.medizon.domain.RoundOff;
import com.medizon.domain.Store;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.*;

/**
 * The cart priced for where it's going: each line at the price of the store that would deliver it. Lines no store
 * there can supply are left out of the totals and listed as unavailable. Lines from different stores become separate
 * orders at checkout, paid for together; one delivery fee applies to the whole cart.
 */
@Getter
public class CartSummary {
    private final List<CartItem> items;
    private final BigDecimal subtotal;
    private final BigDecimal deliveryFee;
    /** Paise added or taken off so the bill is in whole rupees, as the orders will be. */
    private final BigDecimal roundOff;
    private final BigDecimal total;
    private final boolean prescriptionRequired;

    /** Lines must already carry their offers; they decide the stores and so how the bill is rounded. */
    public CartSummary(List<CartItem> items, BigDecimal subtotal, BigDecimal deliveryFee, boolean prescriptionRequired) {
        this.items = items;
        this.subtotal = subtotal;
        this.deliveryFee = deliveryFee;
        this.roundOff = roundOff(storeSubtotals(items), deliveryFee);
        this.total = subtotal.add(deliveryFee).add(roundOff);
        this.prescriptionRequired = prescriptionRequired;
    }

    /**
     * Each store's order is rounded on its own, the way checkout places them: the delivery fee goes on the biggest
     * order, and the round off is the sum of each order's. A cart from one store is just rounded as a whole.
     */
    public static BigDecimal roundOff(List<BigDecimal> storeSubtotals, BigDecimal deliveryFee) {
        if (storeSubtotals.isEmpty()) return RoundOff.of(deliveryFee);
        List<BigDecimal> sorted = new ArrayList<>(storeSubtotals);
        sorted.sort(Comparator.reverseOrder());
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = 0; k < sorted.size(); k++) sum = sum.add(RoundOff.of(sorted.get(k).add(k == 0 ? deliveryFee : BigDecimal.ZERO)));
        return sum;
    }

    private static List<BigDecimal> storeSubtotals(List<CartItem> items) {
        Map<Long, BigDecimal> byStore = new LinkedHashMap<>();
        for (CartItem i : items) {
            if (i.isAvailable()) byStore.merge(i.getOffer().store().getId(), i.getLineTotal(), BigDecimal::add);
        }
        return new ArrayList<>(byStore.values());
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public String getRoundOffLabel() { return RoundOff.label(roundOff); }

    public List<CartItem> getUnavailable() { return items.stream().filter(i -> !i.isAvailable()).toList(); }

    /** The lines one store delivers, and when (null without a location). */
    public record StoreGroup(Store store, DeliveryEstimate delivery, List<CartItem> items) {
        public BigDecimal subtotal() {
            return items.stream().map(CartItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** Lines grouped by the store that delivers them, in cart order; unavailable lines aren't included. */
    public List<StoreGroup> getByStore() {
        Map<Long, StoreGroup> out = new LinkedHashMap<>();
        for (CartItem i : items) {
            if (!i.isAvailable()) continue;
            Offer o = i.getOffer();
            out.computeIfAbsent(o.store().getId(), k -> new StoreGroup(o.store(), o.area() == null ? null
                    : com.medizon.service.DeliveryTimeService.estimate(o.area().getMinDays(), o.area().getMaxDays()),
                    new ArrayList<>())).items().add(i);
        }
        return new ArrayList<>(out.values());
    }

    /** More than one store delivers this cart, so checkout places one order per store. */
    public boolean isSplit() { return getByStore().size() > 1; }
}
