package com.medizon.dto;

import com.medizon.domain.CartItem;
import com.medizon.domain.RoundOff;
import com.medizon.domain.Store;
import com.medizon.service.CouponService;
import com.medizon.service.Discounts;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.*;

/**
 * The cart priced for where it's going: each line at the price of the store that would deliver it. Lines no store
 * there can supply are left out of the totals and listed as unavailable. Lines from different stores become separate
 * orders at checkout, paid for together; one delivery fee applies to the whole cart. A promo comes off the medicines,
 * spread over the lines (see {@link Discounts}).
 */
@Getter
public class CartSummary {
    private final List<CartItem> items;
    private final BigDecimal subtotal;
    private final BigDecimal deliveryFee;
    /** Rupees the promo takes off the medicines; zero without one. */
    private final BigDecimal discount;
    /** The promo as checked against this cart: applied, or why not. Null when none is entered. */
    private final CouponService.Applied coupon;
    /** Each available line's share of {@link #discount}, in {@link #items} order (unavailable lines get zero). */
    private final List<BigDecimal> lineDiscounts;
    /** Paise added or taken off so the bill is in whole rupees, as the orders will be. */
    private final BigDecimal roundOff;
    private final BigDecimal total;
    private final boolean prescriptionRequired;

    /** Lines must already carry their offers; they decide the stores and so how the bill is rounded. */
    public CartSummary(List<CartItem> items, BigDecimal subtotal, BigDecimal deliveryFee, boolean prescriptionRequired) {
        this(items, subtotal, deliveryFee, prescriptionRequired, null);
    }

    public CartSummary(List<CartItem> items, BigDecimal subtotal, BigDecimal deliveryFee, boolean prescriptionRequired,
                       CouponService.Applied coupon) {
        this.items = items;
        this.subtotal = subtotal;
        this.deliveryFee = deliveryFee;
        this.coupon = coupon;
        this.discount = coupon != null && coupon.isOk() ? coupon.discount() : BigDecimal.ZERO;
        this.lineDiscounts = Discounts.split(discount, items.stream()
                .map(i -> i.isAvailable() ? i.getLineTotal() : BigDecimal.ZERO).toList());
        Map<Long, BigDecimal[]> byStore = new LinkedHashMap<>();   // store id -> {medicines, discount}
        for (int k = 0; k < items.size(); k++) {
            CartItem i = items.get(k);
            if (!i.isAvailable()) continue;
            BigDecimal[] row = byStore.computeIfAbsent(i.getOffer().store().getId(), x -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            row[0] = row[0].add(i.getLineTotal());
            row[1] = row[1].add(lineDiscounts.get(k));
        }
        this.roundOff = roundOff(byStore.values().stream().map(r -> r[0]).toList(), byStore.values().stream().map(r -> r[1]).toList(), deliveryFee);
        this.total = subtotal.subtract(discount).add(deliveryFee).add(roundOff);
        this.prescriptionRequired = prescriptionRequired;
    }

    public static BigDecimal roundOff(List<BigDecimal> storeSubtotals, BigDecimal deliveryFee) {
        return roundOff(storeSubtotals, storeSubtotals.stream().map(x -> BigDecimal.ZERO).toList(), deliveryFee);
    }

    /**
     * Each store's order is rounded on its own, the way checkout places them: the delivery fee goes on the biggest
     * order, and the round off is the sum of each order's. A cart from one store is just rounded as a whole.
     */
    public static BigDecimal roundOff(List<BigDecimal> storeSubtotals, List<BigDecimal> storeDiscounts, BigDecimal deliveryFee) {
        if (storeSubtotals.isEmpty()) return RoundOff.of(deliveryFee);
        // The fee goes on the order with the most medicines before the promo, as at checkout
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < storeSubtotals.size(); k++) order.add(k);
        order.sort(Comparator.comparing((Integer k) -> storeSubtotals.get(k)).reversed());
        BigDecimal sum = BigDecimal.ZERO;
        for (int n = 0; n < order.size(); n++) {
            int k = order.get(n);
            sum = sum.add(RoundOff.of(storeSubtotals.get(k).subtract(storeDiscounts.get(k)).add(n == 0 ? deliveryFee : BigDecimal.ZERO)));
        }
        return sum;
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public boolean isDiscounted() { return discount.signum() > 0; }

    /** The applied promo's code, or null. */
    public String getCouponCode() { return isDiscounted() ? coupon.coupon().getCode() : null; }

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
