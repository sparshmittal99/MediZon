package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.repository.MedicineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

/** The master catalogue: products as the admin defines them for every store. What shoppers see is {@link CatalogService}. */
@Service
@RequiredArgsConstructor
public class MedicineService {

    public static final int PAGE_SIZE = 12;
    private final MedicineRepository medicines;
    private final FileStorageService fileStorage;

    /** Every category in the master catalogue, for the admin's product form. */
    public List<String> categories() { return medicines.findActiveCategories(); }

    public List<Medicine> allActive() { return medicines.findByActiveTrueOrderByNameAsc(); }

    public List<Medicine> all() { return medicines.findAll(Sort.by("name")); }

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
        if (m.exceedsMrp(m.getPrice())) throw new BusinessException("The price can't be above the MRP.");
        // The code is never posted with the form: an edit keeps the product's code, a new product gets one once it has an id
        m.setProductCode(m.getId() == null ? null : medicines.findById(m.getId()).map(Medicine::getProductCode).orElse(null));
        Medicine saved = medicines.save(m);
        if (saved.getProductCode() == null) saved.setProductCode(productCode(saved.getId()));
        if (current != null && !current.equals(next)) fileStorage.deleteMedicineImage(current);
        return saved;
    }

    /** "MZP00042": the product's id behind a prefix, so it is unique and never reused. */
    public static String productCode(Long id) { return String.format("MZP%05d", id); }

    /** Gives a code to every product that has none: those from before codes existed, and ones the seeder added. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void assignMissingCodes() {
        medicines.findByProductCodeIsNull().forEach(m -> m.setProductCode(productCode(m.getId())));
    }

    /** Products whose name or code contains {@code q} (any case); all of them when it's empty. */
    public static boolean matches(Medicine m, String q) {
        if (q == null || q.isBlank()) return true;
        String t = q.trim().toLowerCase(java.util.Locale.ROOT);
        return m.getName().toLowerCase(java.util.Locale.ROOT).contains(t)
                || (m.getProductCode() != null && m.getProductCode().toLowerCase(java.util.Locale.ROOT).contains(t));
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
