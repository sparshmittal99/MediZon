package com.medizon.dto;

import com.medizon.domain.Medicine;
import com.medizon.domain.Store;
import com.medizon.domain.StoreArea;
import com.medizon.domain.StoreItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Which store would sell a product to a shopper, and on what terms. With a location, that's the nearest store serving
 * it that has enough in stock; failing that, the nearest one that lists it (shown as out of stock). Without a
 * location, any open store that has it, cheapest first, and no delivery time yet.
 *
 * @param item the store listing, or null when no store (that serves the shopper) lists the product
 * @param area how that store delivers to the shopper; null without a location
 * @param available the most packs one store here can supply: how many fit in one cart line
 */
public record Offer(Medicine medicine, StoreItem item, StoreArea area, int available) {

    public static Offer none(Medicine m) { return new Offer(m, null, null, 0); }

    /** Some store that serves the shopper lists it, in stock or not. */
    public boolean isListed() { return item != null; }

    public Store store() { return item == null ? null : item.getStore(); }

    /** The store's price; the master price when no store lists it. */
    public BigDecimal price() { return item == null ? medicine.getPrice() : item.getSellingPrice(); }

    /** The most packs that can be ordered in one go here (from whichever store has the most). */
    public int stock() { return available; }

    public LocalDate expiryDate() { return item == null ? null : item.getExpiryDate(); }

    public boolean isExpired() { return item != null && item.isExpired(); }

    public boolean canSell(int quantity) { return item != null && item.isSellable() && item.getStock() >= quantity; }

    /**
     * Picks the offer for {@code quantity} packs.
     *
     * @param serving stores delivering to the shopper's PIN code, nearest first; null when there's no location
     * @param listings this product's listings, by store id
     */
    public static Offer pick(Medicine m, List<StoreArea> serving, Map<Long, StoreItem> listings, int quantity) {
        if (!m.isActive()) return none(m);
        if (serving == null) {
            Comparator<StoreItem> cheapest = Comparator.comparing(StoreItem::getSellingPrice).thenComparing(i -> i.getStore().getId());
            List<StoreItem> open = listings.values().stream()
                    .filter(i -> i.isActive() && i.getStore().isActive()).sorted(cheapest).toList();
            int most = open.stream().filter(StoreItem::isSellable).mapToInt(StoreItem::getStock).max().orElse(0);
            return open.stream().filter(i -> i.isSellable() && i.getStock() >= quantity).findFirst()
                    .or(() -> open.stream().findFirst())
                    .map(i -> new Offer(m, i, null, most)).orElse(none(m));
        }
        StoreItem chosen = null, fallback = null;
        StoreArea chosenArea = null, fallbackArea = null;
        int most = 0;
        for (StoreArea area : serving) {
            StoreItem i = listings.get(area.getStore().getId());
            if (i == null || !i.isActive() || !area.getStore().isActive()) continue;
            if (i.isSellable()) most = Math.max(most, i.getStock());
            if (chosen == null && i.isSellable() && i.getStock() >= quantity) { chosen = i; chosenArea = area; }
            if (fallback == null) { fallback = i; fallbackArea = area; }
        }
        if (chosen != null) return new Offer(m, chosen, chosenArea, most);
        return fallback != null ? new Offer(m, fallback, fallbackArea, most) : none(m);
    }
}
