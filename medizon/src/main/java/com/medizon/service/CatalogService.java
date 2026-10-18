package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.StoreArea;
import com.medizon.domain.StoreItem;
import com.medizon.dto.DeliveryEstimate;
import com.medizon.dto.Offer;
import com.medizon.dto.Product;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.StoreAreaRepository;
import com.medizon.repository.StoreItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The shop as seen from a PIN code: which stores deliver there (nearest first), which products they sell, and which
 * store would deliver each product at what price. A null or unserved PIN code sees everything any open store sells,
 * without delivery times.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService {

    private final MedicineRepository medicines;
    private final StoreAreaRepository areas;
    private final StoreItemRepository items;
    private final DeliveryAreaService deliveryAreas;

    /** Open stores delivering to this PIN code, nearest first; empty when it isn't served. */
    public List<StoreArea> serving(String pincode) {
        String p = DeliveryAreaService.normalize(pincode);
        if (p == null || !deliveryAreas.isListed(p)) return List.of();
        return areas.serving(p);
    }

    /** The stores to shop from: those serving the PIN code, or null (any open store) when none do. */
    private List<StoreArea> scope(String pincode) {
        List<StoreArea> s = serving(pincode);
        return s.isEmpty() ? null : s;
    }

    private static Set<Long> storeIds(List<StoreArea> scope) {
        return scope.stream().map(a -> a.getStore().getId()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ------------------------------------------------------------------ offers

    public Offer offer(Medicine m, String pincode, int quantity) {
        return offers(List.of(m), pincode, quantity).get(m.getId());
    }

    /** One offer per medicine, for {@code quantity} packs each. */
    public Map<Long, Offer> offers(Collection<Medicine> list, String pincode, int quantity) {
        Map<Medicine, Integer> wanted = new LinkedHashMap<>();
        list.forEach(m -> wanted.put(m, quantity));
        return offers(wanted, pincode);
    }

    /** One offer per medicine for the packs wanted of each, by medicine id. */
    public Map<Long, Offer> offers(Map<Medicine, Integer> wanted, String pincode) {
        Map<Long, Offer> out = new HashMap<>();
        if (wanted.isEmpty()) return out;
        List<StoreArea> scope = scope(pincode);
        Set<Long> ids = wanted.keySet().stream().map(Medicine::getId).collect(Collectors.toSet());
        List<StoreItem> found = scope == null ? items.findOpenFor(ids) : items.findFor(ids, storeIds(scope));
        Map<Long, Map<Long, StoreItem>> byMedicine = new HashMap<>();
        found.forEach(i -> byMedicine.computeIfAbsent(i.getMedicine().getId(), k -> new HashMap<>()).put(i.getStore().getId(), i));
        wanted.forEach((m, qty) -> out.put(m.getId(), Offer.pick(m, scope, byMedicine.getOrDefault(m.getId(), Map.of()), Math.max(1, qty))));
        return out;
    }

    public Product product(Medicine m, String pincode) { return new Product(m, offer(m, pincode, 1)); }

    public List<Product> products(List<Medicine> list, String pincode) {
        Map<Long, Offer> o = offers(list, pincode, 1);
        return list.stream().map(m -> new Product(m, o.get(m.getId()))).toList();
    }

    /** When the store that would deliver this offer gets it there; empty without a location. */
    public Optional<DeliveryEstimate> estimate(Offer offer) {
        if (offer == null || offer.area() == null || !offer.isListed()) return Optional.empty();
        return Optional.of(DeliveryTimeService.estimate(offer.area().getMinDays(), offer.area().getMaxDays()));
    }

    // ------------------------------------------------------------------ the shop

    public Page<Product> search(String q, String category, int page, String pincode) {
        String query = q == null ? "" : q.trim();
        String cat = category == null ? "" : category;
        PageRequest pr = PageRequest.of(Math.max(page, 0), MedicineService.PAGE_SIZE, Sort.by("name"));
        List<StoreArea> scope = scope(pincode);
        Page<Medicine> found = scope == null ? medicines.searchSold(query, cat, pr)
                : medicines.searchIn(query, cat, storeIds(scope), pr);
        Map<Long, Offer> o = offers(found.getContent(), pincode, 1);
        return found.map(m -> new Product(m, o.get(m.getId())));
    }

    public List<String> categories(String pincode) {
        List<StoreArea> scope = scope(pincode);
        return scope == null ? medicines.findActiveCategories() : medicines.categoriesIn(storeIds(scope));
    }

    /** Everything on sale here, A to Z. */
    public List<Medicine> sold(String pincode) {
        List<StoreArea> scope = scope(pincode);
        return scope == null ? medicines.sold() : medicines.soldIn(storeIds(scope));
    }

    /**
     * What can be ordered here right now, A to Z. The home page shows only these; out-of-stock products stay in the
     * shop, where shoppers can ask to be told when they're back.
     */
    public List<Product> inStock(String pincode) {
        return products(sold(pincode), pincode).stream().filter(Product::isInStock).toList();
    }

    /** The newest of {@code inStock}. */
    public static List<Product> featured(List<Product> inStock) {
        return inStock.stream().sorted(Comparator.comparing(Product::getId).reversed()).limit(8).toList();
    }

    /** {@code inStock} with the biggest discounts first, for the home page's deals card. */
    public static List<Product> deals(List<Product> inStock, int limit) {
        return inStock.stream()
                .filter(p -> p.getDiscountPercent() > 0)
                .sorted(Comparator.comparingInt(Product::getDiscountPercent).reversed())
                .limit(limit).toList();
    }

    /** A few of {@code inStock} from each category, fullest categories first, for the home page's shelves. */
    public static Map<String, List<Product>> shelves(List<Product> inStock, int maxShelves, int perShelf) {
        Map<String, List<Product>> byCategory = new LinkedHashMap<>();
        for (Product p : inStock) {
            if (p.getCategory() != null) byCategory.computeIfAbsent(p.getCategory(), c -> new ArrayList<>()).add(p);
        }
        Map<String, List<Product>> shelves = new LinkedHashMap<>();
        byCategory.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<Product>> e) -> e.getValue().size()).reversed())
                .limit(maxShelves)
                .forEach(e -> shelves.put(e.getKey(), e.getValue().subList(0, Math.min(perShelf, e.getValue().size()))));
        return shelves;
    }
}
