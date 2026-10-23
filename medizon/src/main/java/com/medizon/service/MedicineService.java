package com.medizon.service;

import com.medizon.domain.Category;
import com.medizon.domain.Medicine;
import com.medizon.repository.CategoryRepository;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.StoreItemRepository;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The master catalogue: products as the admin defines them for every store. What shoppers see is {@link CatalogService}. */
@Service
@RequiredArgsConstructor
public class MedicineService {

    public static final int PAGE_SIZE = 12;
    private final MedicineRepository medicines;
    private final FileStorageService fileStorage;
    private final CategoryRepository categoryRepo;
    private final StoreItemRepository storeItems;

    /** Every category in the master catalogue A to Z, with those no product is in yet: for the admin's pages. */
    public List<String> categories() {
        return categoryRepo.findAllByOrderByNameAsc().stream().map(Category::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** A new category for products to go in. Returns its name as saved. */
    @Transactional
    public String addCategory(String name) {
        String n = tidy(name);
        if (n.isEmpty()) throw new BusinessException("Enter a name for the category.");
        if (n.length() > Category.MAX_NAME) throw new BusinessException("Keep the category name to " + Category.MAX_NAME + " characters.");
        categoryRepo.findFirstByNameIgnoreCase(n).ifPresent(c -> {
            throw new BusinessException(c.getName() + " is already a category.");
        });
        return categoryRepo.save(new Category(n)).getName();
    }

    /**
     * The category as the catalogue spells it when one of {@code known} matches whatever the case ("pain & fever" is
     * "Pain & Fever"), else the name with its spaces tidied.
     */
    public static String canonicalCategory(String name, Collection<String> known) {
        String n = tidy(name);
        return known.stream().filter(k -> k.equalsIgnoreCase(n)).findFirst().orElse(n);
    }

    private static String tidy(String name) { return name == null ? "" : name.trim().replaceAll("\\s+", " "); }

    /** The product's category as the catalogue spells it, added to the categories when it's new. */
    private String registerCategory(String name) {
        String n = tidy(name);
        if (n.isEmpty()) return name;
        return categoryRepo.findFirstByNameIgnoreCase(n).map(Category::getName).orElseGet(() -> categoryRepo.save(new Category(n)).getName());
    }

    /**
     * Categories from before they were kept on their own: every one a product is in becomes a category, and products
     * whose category differs only in case ("Pain & fever", "Pain & Fever") all take the category's one spelling.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void registerMissingCategories() {
        medicines.findAllCategories().forEach(this::registerCategory);
        categoryRepo.findAll().forEach(c -> medicines.moveCategory(c.getName(), c.getName()));
    }

    /**
     * Renames a category, with every product in it, in every store and for shoppers at once. Renaming it to another
     * category's name merges the two. A change of case only ("Pain & fever" to "Pain & Fever") is a rename too.
     * Returns what happened, for the admin.
     */
    @Transactional
    public String renameCategory(String from, String to) {
        String f = tidy(from), t = tidy(to);
        if (f.isEmpty()) throw new BusinessException("Choose the category to rename.");
        if (t.isEmpty()) throw new BusinessException("Enter the new name for " + f + ".");
        if (t.length() > Category.MAX_NAME) throw new BusinessException("Keep the category name to " + Category.MAX_NAME + " characters.");
        Category source = categoryRepo.findFirstByNameIgnoreCase(f)
                .orElseThrow(() -> new BusinessException(f + " isn't a category any more."));
        if (source.getName().equals(t)) throw new BusinessException(t + " already has that name.");
        Category target = categoryRepo.findFirstByNameIgnoreCase(t).filter(c -> !c.getId().equals(source.getId())).orElse(null);
        String old = source.getName();
        if (target == null) {
            source.setName(t);
            categoryRepo.saveAndFlush(source);
            int moved = medicines.moveCategory(old, t);
            return old + " is now " + t + (moved == 1 ? " (1 product)." : " (" + moved + " products).");
        }
        int moved = medicines.moveCategory(old, target.getName());
        categoryRepo.delete(source);
        return old + " merged into " + target.getName() + (moved == 1 ? ": 1 product moved." : ": " + moved + " products moved.");
    }

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
        m.setCategory(registerCategory(m.getCategory()));
        // The code is never posted with the form: an edit keeps the product's code, a new product gets one once it has an id
        m.setProductCode(m.getId() == null ? null : medicines.findById(m.getId()).map(Medicine::getProductCode).orElse(null));
        Medicine saved = medicines.save(m);
        if (saved.getProductCode() == null) saved.setProductCode(productCode(saved.getId()));
        if (current != null && !current.equals(next)) fileStorage.deleteMedicineImage(current);
        // Stores sell the product as it is now; a store price above a lowered MRP comes down to the MRP
        if (m.getId() != null) {
            storeItems.findByMedicine(saved).stream().filter(i -> saved.exceedsMrp(i.getPrice())).forEach(i -> i.setPrice(saved.getMrp()));
        }
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

    /** Products whose name, code or category contains {@code q} (any case); all of them when it's empty. */
    public static boolean matches(Medicine m, String q) {
        if (q == null || q.isBlank()) return true;
        String t = q.trim().toLowerCase(Locale.ROOT);
        return m.getName().toLowerCase(Locale.ROOT).contains(t)
                || (m.getProductCode() != null && m.getProductCode().toLowerCase(Locale.ROOT).contains(t))
                || (m.getCategory() != null && m.getCategory().toLowerCase(Locale.ROOT).contains(t));
    }

    /** Products in {@code category} (any case); all of them when it's empty. */
    public static boolean inCategory(Medicine m, String category) {
        return category == null || category.isBlank() || category.trim().equalsIgnoreCase(m.getCategory());
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
