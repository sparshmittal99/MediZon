package com.medizon.service;

import com.medizon.domain.Order;
import com.medizon.domain.Store;
import com.medizon.domain.StoreArea;
import com.medizon.domain.User;
import com.medizon.repository.StoreAreaRepository;
import com.medizon.repository.StoreItemRepository;
import com.medizon.repository.StoreRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Stores, and the PIN codes each one serves with its delivery time there. Only the admin changes these. Also answers
 * "which store's data may this staff account touch": a store account only its own store, the admin every store.
 */
@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository stores;
    private final StoreAreaRepository areas;
    private final StoreItemRepository items;
    private final UserRepository users;
    private final DeliveryAreaService deliveryAreas;

    public List<Store> all() { return stores.findAllByOrderByNameAsc(); }

    public List<Store> open() { return stores.findByActiveTrueOrderByNameAsc(); }

    public Store get(Long id) { return stores.findById(id).orElseThrow(() -> new BusinessException("That store doesn't exist.")); }

    @Transactional
    public Store save(Store form) {
        String name = form.getName().trim();
        Store s = form.getId() == null ? new Store() : get(form.getId());
        if (!name.equalsIgnoreCase(Objects.toString(s.getName(), "")) && stores.existsByNameIgnoreCase(name)) {
            throw new BusinessException("There's already a store called " + name + ".");
        }
        s.setName(name);
        s.setAddressLine(trim(form.getAddressLine()));
        s.setCity(trim(form.getCity()));
        s.setState(trim(form.getState()));
        s.setPincode(trim(form.getPincode()));
        s.setPhone(trim(form.getPhone()));
        s.setEmail(trim(form.getEmail()));
        s.setGstin(form.getGstin() == null || form.getGstin().isBlank() ? null : form.getGstin().trim().toUpperCase());
        s.setDrugLicense(trim(form.getDrugLicense()));
        if (form.getId() == null) s.setActive(true);
        return stores.save(s);
    }

    @Transactional
    public Store setActive(Long id, boolean active) {
        Store s = get(id);
        s.setActive(active);
        return s;
    }

    public long listingCount(Store s) { return items.countByStoreAndActiveTrue(s); }

    public List<User> staff(Store s) {
        return users.findByStoreOrderByFullNameAsc(s).stream().filter(u -> !u.isDeleted()).toList();
    }

    // ------------------------------------------------------------------ PIN codes served

    public List<StoreArea> areas(Store s) { return areas.findByStoreOrderByPincodeAsc(s); }

    /** Stores serving each listed PIN code, quickest first. */
    public Map<String, List<StoreArea>> areasByPincode() {
        Map<String, List<StoreArea>> out = new HashMap<>();
        areas.findAll().stream()
                .sorted(Comparator.comparingInt(StoreArea::getMaxDays).thenComparingInt(StoreArea::getMinDays))
                .forEach(a -> out.computeIfAbsent(a.getPincode(), k -> new ArrayList<>()).add(a));
        return out;
    }

    /**
     * The store delivers to these PIN codes in {@code minDays} to {@code maxDays} (0 to 0 is same-day). New PIN codes
     * are added to the delivery areas; ones the store already serves get the new times. Returns how many were saved.
     */
    @Transactional
    public int serve(Long storeId, String pincodes, int minDays, int maxDays) {
        Store s = get(storeId);
        checkDays(minDays, maxDays);
        List<String> pins = DeliveryAreaService.parse(pincodes);
        List<String> unlisted = pins.stream().filter(p -> deliveryAreas.find(p).isEmpty()).toList();
        if (!unlisted.isEmpty()) deliveryAreas.add(String.join(" ", unlisted), null, null, null);
        for (String p : pins) {
            StoreArea a = areas.findByStoreAndPincode(s, p).orElseGet(() -> {
                StoreArea n = new StoreArea();
                n.setStore(s);
                n.setPincode(p);
                return n;
            });
            a.setMinDays(minDays);
            a.setMaxDays(maxDays);
            areas.save(a);
        }
        return pins.size();
    }

    @Transactional
    public StoreArea setDays(Long areaId, int minDays, int maxDays) {
        checkDays(minDays, maxDays);
        StoreArea a = area(areaId);
        a.setMinDays(minDays);
        a.setMaxDays(maxDays);
        return a;
    }

    @Transactional
    public StoreArea stopServing(Long areaId) {
        StoreArea a = area(areaId);
        areas.delete(a);
        return a;
    }

    private StoreArea area(Long id) {
        return areas.findById(id).orElseThrow(() -> new BusinessException("That store no longer serves this PIN code."));
    }

    private static void checkDays(int min, int max) {
        if (min < 0 || max < 0 || min > 30 || max > 30) throw new BusinessException("Delivery days must be between 0 and 30.");
        if (max < min) throw new BusinessException("\"Delivery to\" can't be before \"delivery from\".");
    }

    // ------------------------------------------------------------------ staff access

    /** The store a staff account is limited to: its own store for a store account, none (every store) otherwise. */
    public Optional<Store> scopeOf(User u) {
        if (!u.isStoreStaff()) return Optional.empty();
        if (u.getStore() == null) throw new BusinessException("Your account isn't linked to a store yet. Ask the admin.");
        return Optional.of(u.getStore());
    }

    /** Throws unless this account may work on the store's data. */
    public void checkAccess(User u, Store s) {
        scopeOf(u).ifPresent(own -> {
            if (s == null || !own.getId().equals(s.getId())) throw new BusinessException("That belongs to another store.");
        });
    }

    public void checkAccess(User u, Order o) { checkAccess(u, o.getStore()); }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
