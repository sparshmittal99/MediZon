package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.Store;
import com.medizon.domain.StoreItem;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.StoreItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Each store's catalogue: which master products it sells, its stock and batch expiry, and its own price for OTC
 * medicines (at most the MRP). Prescription medicines always sell at the master price the admin sets.
 */
@Service
@RequiredArgsConstructor
public class StoreCatalogService {

    /** Stock (in packs) below which a listing shows up as low on stock. */
    public static final int LOW_STOCK_BELOW = 50;

    private final StoreItemRepository items;
    private final MedicineRepository medicines;
    private final ApplicationEventPublisher events;

    public List<StoreItem> items(Store s) { return items.findByStoreOrderByName(s); }

    public StoreItem item(Long id) {
        return items.findById(id).orElseThrow(() -> new BusinessException("That listing doesn't exist."));
    }

    public Optional<StoreItem> find(Store s, Medicine m) { return items.findByStoreAndMedicine(s, m); }

    /** Master products this store doesn't sell yet, A to Z, for "add from the master catalogue". */
    public List<Medicine> notListed(Store s) {
        Set<Long> listed = new HashSet<>();
        items(s).forEach(i -> listed.add(i.getMedicine().getId()));
        return medicines.findByActiveTrueOrderByNameAsc().stream().filter(m -> !listed.contains(m.getId())).toList();
    }

    /** Adds a master product to the store's catalogue, or updates it when it's already there. */
    @Transactional
    public StoreItem list(Store s, Long medicineId, BigDecimal price, int stock, LocalDate expiry) {
        Medicine m = medicines.findById(medicineId).orElseThrow(() -> new BusinessException("Choose a product from the catalogue."));
        StoreItem i = items.findByStoreAndMedicine(s, m).orElseGet(() -> {
            StoreItem n = new StoreItem();
            n.setStore(s);
            n.setMedicine(m);
            return n;
        });
        apply(i, price, stock, expiry);
        i.setActive(true);
        items.save(i);
        events.publishEvent(new StockChanged(m.getId()));
        return i;
    }

    /** A product to start selling, with its stock and batch expiry (null when not known yet). */
    public record NewListing(Long medicineId, int stock, LocalDate expiry) {}

    /** Adds several master products to the store's catalogue at their catalogue price; all of them or, on a problem, none. */
    @Transactional
    public int listAll(Store s, List<NewListing> listings) {
        listings.forEach(l -> list(s, l.medicineId(), null, l.stock(), l.expiry()));
        return listings.size();
    }

    @Transactional
    public StoreItem update(Long itemId, BigDecimal price, int stock, LocalDate expiry) {
        StoreItem i = item(itemId);
        apply(i, price, stock, expiry);
        events.publishEvent(new StockChanged(i.getMedicine().getId()));
        return i;
    }

    @Transactional
    public StoreItem setActive(Long itemId, boolean active) {
        StoreItem i = item(itemId);
        i.setActive(active);
        if (active) events.publishEvent(new StockChanged(i.getMedicine().getId()));
        return i;
    }

    private static void apply(StoreItem i, BigDecimal price, int stock, LocalDate expiry) {
        if (stock < 0) throw new BusinessException("Stock can't be negative.");
        Medicine m = i.getMedicine();
        if (m.isPrescriptionRequired() || price == null) {
            i.setPrice(null);      // prescription medicines: the admin's price everywhere; empty: the master price
        } else {
            if (price.signum() <= 0) throw new BusinessException("Enter a price above zero.");
            if (m.exceedsMrp(price)) throw new BusinessException(m.getName() + " can't sell above its MRP of ₹" + m.getMrp() + ".");
            i.setPrice(price);
        }
        i.setStock(stock);
        i.setExpiryDate(expiry);
    }

    public List<StoreItem> lowStock(Store s) {
        return s == null ? items.lowStock(LOW_STOCK_BELOW) : items.lowStockIn(LOW_STOCK_BELOW, s);
    }

    /** For the master catalogue: how many stores sell each product and their stock in total. */
    public Map<Long, int[]> coverage() {
        Map<Long, int[]> out = new HashMap<>();
        items.findAll().stream().filter(i -> i.isActive() && i.getStore().isActive()).forEach(i -> {
            int[] c = out.computeIfAbsent(i.getMedicine().getId(), k -> new int[2]);
            c[0]++;
            c[1] += i.getStock();
        });
        return out;
    }
}
