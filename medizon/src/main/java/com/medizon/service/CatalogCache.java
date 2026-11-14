package com.medizon.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.medizon.domain.Medicine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The shop's catalogue lists, kept in memory: which stores serve a PIN code, what they sell and its categories. Every
 * page shows the categories and every search keystroke ranks what's sold, so without this each one was a database
 * query. Live stock, prices for the cart and holds are never kept here: offers are still worked out per request.
 *
 * <p>{@link CatalogChangeWatcher} empties it once a change to a product, store, listing, delivery area or category is
 * committed, so the shop shows the change on the next request. Entries also expire after {@link #TTL} as a safety
 * net. A list read while a change was being committed is returned but not kept, so it can't outlive the change.
 */
@Component
public class CatalogCache implements MeterBinder {

    static final Duration TTL = Duration.ofMinutes(10);

    /** PIN code -> ids of the open stores delivering there, sorted; empty: not served, so any open store. */
    private final Cache<String, List<Long>> stores = build(10_000);
    /** Store ids (empty: any open store) -> what they sell, A to Z. */
    private final Cache<List<Long>, List<Medicine>> sold = build(200);
    /** Store ids (empty: any open store) -> the categories of what they sell. */
    private final Cache<List<Long>, List<String>> categories = build(200);

    /** Moves on with every change, so a list read from before it isn't kept. */
    private final AtomicLong generation = new AtomicLong();

    private static <K, V> Cache<K, V> build(long maxEntries) {
        return Caffeine.newBuilder().maximumSize(maxEntries).expireAfterWrite(TTL).recordStats().build();
    }

    List<Long> stores(String pincode, Supplier<List<Long>> load) {
        return get(stores, pincode == null ? "" : pincode, load);
    }

    List<Medicine> sold(List<Long> storeIds, Supplier<List<Medicine>> load) {
        return get(sold, storeIds, load);
    }

    List<String> categories(List<Long> storeIds, Supplier<List<String>> load) {
        return get(categories, storeIds, load);
    }

    private <K, V> List<V> get(Cache<K, List<V>> cache, K key, Supplier<List<V>> load) {
        List<V> hit = cache.getIfPresent(key);
        if (hit != null) return hit;
        long before = generation.get();
        // Read-only and shared between requests; nulls allowed (a product without a category)
        List<V> loaded = Collections.unmodifiableList(new ArrayList<>(load.get()));
        if (generation.get() == before) cache.put(key, loaded);
        return loaded;
    }

    /** Something the lists are made from changed: they're read afresh from the database on the next request. */
    public void clear() {
        generation.incrementAndGet();
        stores.invalidateAll();
        sold.invalidateAll();
        categories.invalidateAll();
    }

    /** Hit rates and sizes on /actuator/prometheus, as cache_gets_total{cache="catalog.sold",result="hit"} etc. */
    @Override
    public void bindTo(MeterRegistry registry) {
        CaffeineCacheMetrics.monitor(registry, stores, "catalog.stores");
        CaffeineCacheMetrics.monitor(registry, sold, "catalog.sold");
        CaffeineCacheMetrics.monitor(registry, categories, "catalog.categories");
    }
}
