package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.repository.MedicineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MedicineService {

    public static final int PAGE_SIZE = 12;
    /** Stock (in packs) below which a medicine shows up as low on stock for admins. */
    public static final int LOW_STOCK_BELOW = 50;
    private final MedicineRepository medicines;
    private final FileStorageService fileStorage;

    public Page<Medicine> search(String q, String category, int page) {
        return medicines.search(q == null ? "" : q.trim(),
                category == null ? "" : category,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by("name")));
    }

    public List<String> categories() { return medicines.findActiveCategories(); }

    public List<Medicine> featured() { return medicines.findTop8ByActiveTrueOrderByIdDesc(); }

    public List<Medicine> allActive() { return medicines.findByActiveTrueOrderByNameAsc(); }

    /** In-stock medicines with the biggest discounts first, for the home page's deals card. */
    public List<Medicine> deals(int limit) {
        return medicines.findByActiveTrueOrderByNameAsc().stream()
                .filter(m -> m.getDiscountPercent() > 0 && m.isInStock())
                .sorted(Comparator.comparingInt(Medicine::getDiscountPercent).reversed())
                .limit(limit).toList();
    }

    /** A few medicines from each category, fullest categories first, for the home page's shelves. */
    public Map<String, List<Medicine>> shelves(int maxShelves, int perShelf) {
        Map<String, List<Medicine>> byCategory = new LinkedHashMap<>();
        for (Medicine m : medicines.findByActiveTrueOrderByNameAsc()) {
            if (m.getCategory() != null) byCategory.computeIfAbsent(m.getCategory(), c -> new ArrayList<>()).add(m);
        }
        Map<String, List<Medicine>> shelves = new LinkedHashMap<>();
        byCategory.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<Medicine>> e) -> e.getValue().size()).reversed())
                .limit(maxShelves)
                .forEach(e -> shelves.put(e.getKey(), e.getValue().subList(0, Math.min(perShelf, e.getValue().size()))));
        return shelves;
    }

    public List<Medicine> all() { return medicines.findAll(Sort.by("name")); }

    public List<Medicine> lowStock(int below) { return medicines.findByActiveTrueAndStockLessThanOrderByStockAsc(below); }

    public Medicine get(Long id) {
        return medicines.findById(id).orElseThrow(() -> new BusinessException("That medicine doesn't exist."));
    }

    /**
     * Saves the medicine with its optional image: an uploaded file wins over a link, "remove" clears it,
     * and with none of these the current image is kept. A replaced upload is deleted from disk.
     */
    @Transactional
    public Medicine save(Medicine m, MultipartFile imageFile, String imageLink, boolean removeImage) {
        String current = currentImage(m.getId());
        String next = current;
        if (removeImage) {
            next = null;
        } else if (imageFile != null && !imageFile.isEmpty()) {
            next = fileStorage.storeMedicineImage(imageFile);
        } else if (imageLink != null && !imageLink.isBlank()) {
            next = validLink(imageLink.trim());
        }
        m.setImageUrl(next);
        DeliveryTimeService.normalize(m);
        Medicine saved = medicines.save(m);
        if (current != null && !current.equals(next)) fileStorage.deleteMedicineImage(current);
        return saved;
    }

    /** The image stored for this medicine, or null for a new one. */
    public String currentImage(Long id) {
        return id == null ? null : medicines.findById(id).map(Medicine::getImageUrl).orElse(null);
    }

    private static String validLink(String link) {
        try {
            URI uri = new URI(link);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null && link.length() <= 255) return link;
        } catch (URISyntaxException ignored) { }
        throw new BusinessException("Enter an image link that starts with http:// or https://.");
    }

    @Transactional
    public void toggleActive(Long id) {
        Medicine m = get(id);
        m.setActive(!m.isActive());
    }
}
