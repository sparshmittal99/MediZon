package com.medizon.web;

import com.medizon.domain.Medicine;
import com.medizon.domain.NotificationType;
import com.medizon.domain.Role;
import com.medizon.domain.Store;
import com.medizon.domain.StoreItem;
import com.medizon.domain.User;
import com.medizon.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Catalogues. The admin keeps the master catalogue (every product, its MRP, tax details and the price prescription
 * medicines sell at everywhere) and can open any store's catalogue. A store account keeps its own store's catalogue:
 * which products it sells, its stock and expiry, and its price for OTC medicines. It can add a product that isn't in
 * the master catalogue yet; after that, only the admin changes the product's details.
 */
@Controller
@RequestMapping("/admin/medicines")
@RequiredArgsConstructor
public class CatalogAdminController {

    private final MedicineService medicineService;
    private final StoreCatalogService storeCatalog;
    private final StoreService storeService;
    private final CurrentUser currentUser;
    private final StaffAlerts staffAlerts;
    private final CatalogImportService catalogImport;

    /** The image is chosen through upload / link fields, never posted directly; the product code is given automatically. */
    @InitBinder("medicine")
    void medicineBinder(WebDataBinder binder) { binder.setDisallowedFields("imageUrl", "productCode"); }

    /** Most products the "Add products" search suggests at a time. */
    private static final int LOOKUP_LIMIT = 15;

    /**
     * A store account sees its own catalogue; the admin the master catalogue, or one store's with {@code store}.
     * {@code q} narrows either to products whose name or product code contains it.
     */
    @GetMapping
    public String list(@RequestParam(required = false) Long store, @RequestParam(required = false) String q, Model model) {
        User me = currentUser.get();
        Store s = storeService.scopeOf(me).orElse(store == null ? null : storeService.get(store));
        String term = q == null || q.isBlank() ? null : q.trim();
        model.addAttribute("active", "medicines");
        model.addAttribute("stores", me.isAdmin() ? storeService.all() : List.of());
        model.addAttribute("q", term);
        if (s == null) {
            model.addAttribute("medicines", medicineService.all().stream().filter(m -> MedicineService.matches(m, term)).toList());
            model.addAttribute("coverage", storeCatalog.coverage());
            model.addAttribute("importColumns", CatalogImportService.MASTER);
            return "admin/medicines";
        }
        List<StoreItem> items = storeCatalog.items(s);
        model.addAttribute("store", s);
        model.addAttribute("items", items.stream().filter(i -> MedicineService.matches(i.getMedicine(), term)).toList());
        model.addAttribute("itemCount", items.size());
        model.addAttribute("importColumns", CatalogImportService.STORE);
        model.addAttribute("lowBelow", StoreCatalogService.LOW_STOCK_BELOW);
        return "admin/store-catalog";
    }

    // ---------------------------------------------------------------- master products

    /** A new product: the admin adds it to the master catalogue; a store also starts selling it right away. */
    @GetMapping("/new")
    public String newMedicine(@RequestParam(required = false) Long store, Model model) {
        return form(new Medicine(), listingStore(store), model);
    }

    @GetMapping("/{id}/edit")
    public String editMedicine(@PathVariable Long id, Model model) {
        if (!currentUser.get().isAdmin()) throw new BusinessException("Only the admin changes a product's details. You can change your price, stock and expiry.");
        return form(medicineService.get(id), null, model);
    }

    private String form(Medicine m, Store listIn, Model model) {
        model.addAttribute("medicine", m);
        model.addAttribute("categories", medicineService.categories());
        model.addAttribute("listIn", listIn);
        model.addAttribute("active", "medicines");
        return "admin/medicine-form";
    }

    /**
     * What a store can't decide when it adds a product: whether it needs a prescription, how it's taxed and whether
     * it can be returned. It starts prescription-only with the catalogue's defaults, whatever was posted, and the
     * admin is asked to review it.
     */
    private static void applyStoreDefaults(Medicine m) {
        Medicine defaults = new Medicine();
        m.setPrescriptionRequired(true);
        m.setHsnCode(defaults.getHsnCode());
        m.setGstRate(defaults.getGstRate());
        m.setReturnable(defaults.isReturnable());
        m.setActive(true);
    }

    /** The store a new product is listed in: a store account's own, or the one the admin chose (if any). */
    private Store listingStore(Long storeId) {
        User me = currentUser.get();
        return storeService.scopeOf(me).orElse(storeId == null ? null : storeService.get(storeId));
    }

    @PostMapping("/save")
    public String saveMedicine(@Valid @ModelAttribute("medicine") Medicine medicine, BindingResult result,
                               @RequestParam(required = false) MultipartFile imageFile,
                               @RequestParam(required = false) String imageLink,
                               @RequestParam(defaultValue = "false") boolean removeImage,
                               @RequestParam(required = false) Long listStoreId,
                               @RequestParam(required = false) BigDecimal listPrice,
                               @RequestParam(defaultValue = "0") int listStock,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate listExpiry,
                               Model model, RedirectAttributes ra) {
        User me = currentUser.get();
        if (!me.isAdmin() && medicine.getId() != null) {
            throw new BusinessException("Only the admin changes a product's details. You can change your price, stock and expiry.");
        }
        if (!me.isAdmin()) applyStoreDefaults(medicine);
        Store listIn = medicine.getId() == null ? listingStore(listStoreId) : null;
        if (result.hasErrors()) {
            medicine.setImageUrl(medicineService.currentImage(medicine.getId()));
            return form(medicine, listIn, model);
        }
        Medicine saved = medicineService.save(medicine, imageFile, imageLink, removeImage);
        if (!me.isAdmin()) {
            staffAlerts.notify(Role.ADMIN, NotificationType.INFO, "New product to review: " + saved.getName(),
                    (listIn != null ? listIn.getName() : me.getFullName()) + " added it. It sells only with a prescription,"
                            + " at 5% GST, until you review it.", "/admin/medicines/" + saved.getId() + "/edit");
        }
        if (listIn != null) {
            storeCatalog.list(listIn, saved.getId(), listPrice, listStock, listExpiry);
            ra.addFlashAttribute("success", saved.getName() + " added to the catalogue and listed at " + listIn.getName() + ".");
            return "redirect:/admin/medicines" + (me.isAdmin() ? "?store=" + listIn.getId() : "");
        }
        ra.addFlashAttribute("success", saved.getName() + " saved.");
        return "redirect:/admin/medicines";
    }

    /** Hides a product from every store, or lists it again. */
    @PostMapping("/{id}/toggle")
    public String toggleMedicine(@PathVariable Long id) {
        if (!currentUser.get().isAdmin()) throw new BusinessException("Only the admin can hide a product everywhere.");
        medicineService.toggleActive(id);
        return "redirect:/admin/medicines";
    }

    // ---------------------------------------------------------------- imports from Excel, CSV or PDF

    /** The admin imports products into the master catalogue from a sheet. */
    @PostMapping("/import")
    public String importMaster(@RequestParam(required = false) MultipartFile file, RedirectAttributes ra) {
        if (!currentUser.get().isAdmin()) throw new BusinessException("Only the admin imports into the master catalogue.");
        return reported(catalogImport.importMaster(file), ra, "redirect:/admin/medicines");
    }

    /** A store (or the admin, for any store) imports its stock, expiry and prices from a sheet. */
    @PostMapping("/import/store")
    public String importStore(@RequestParam Long storeId, @RequestParam(required = false) MultipartFile file, RedirectAttributes ra) {
        Store s = storeService.get(storeId);
        storeService.checkAccess(currentUser.get(), s);
        return reported(catalogImport.importStore(s, file), ra, back(s));
    }

    private static String reported(CatalogImportService.Report report, RedirectAttributes ra, String to) {
        ra.addFlashAttribute(report.skipped().isEmpty() ? "success" : "error", report.getSummary()
                + (report.skipped().isEmpty() ? "" : " The skipped rows are listed below; fix them in the sheet and import it again."));
        ra.addFlashAttribute("importReport", report);
        return to;
    }

    /**
     * The sheet to fill in for an import, with what's already there: every master product for the admin
     * ({@code store} empty), or one store's listings.
     */
    @GetMapping("/import/template.xlsx")
    public ResponseEntity<byte[]> importTemplate(@RequestParam(required = false) Long store) {
        User me = currentUser.get();
        Store s = storeService.scopeOf(me).orElse(store == null ? null : storeService.get(store));
        if (s == null && !me.isAdmin()) throw new BusinessException("Only the admin imports into the master catalogue.");
        if (s != null) storeService.checkAccess(me, s);
        String name = s == null ? "medizon-master-catalogue.xlsx"
                : "medizon-" + s.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "") + "-catalogue.xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(catalogImport.template(s));
    }

    // ---------------------------------------------------------------- a store's listings

    /** A product the "Add products" search suggests. */
    public record Suggestion(Long id, String code, String name, String manufacturer, boolean rx, BigDecimal price) {}

    /** Master products the store doesn't sell yet whose name or product code contains {@code q}, for "Add products". */
    @GetMapping("/lookup")
    @ResponseBody
    public List<Suggestion> lookup(@RequestParam Long storeId, @RequestParam(defaultValue = "") String q) {
        Store s = storeService.get(storeId);
        storeService.checkAccess(currentUser.get(), s);
        if (q.isBlank()) return List.of();
        return storeCatalog.notListed(s).stream().filter(m -> MedicineService.matches(m, q)).limit(LOOKUP_LIMIT)
                .map(m -> new Suggestion(m.getId(), m.getProductCode(), m.getName(), m.getManufacturer(), m.isPrescriptionRequired(),
                        m.getMrp() != null ? m.getMrp() : m.getPrice()))
                .toList();
    }

    /**
     * Starts selling several master products in a store at once, each with its stock and batch expiry (both optional:
     * they can be set afterwards in the catalogue). Prices start at the catalogue price.
     */
    @PostMapping("/listings/bulk")
    public String listMany(@RequestParam Long storeId, @RequestParam(required = false) List<Long> medicineId,
                           @RequestParam(required = false) List<String> stock, @RequestParam(required = false) List<String> expiryDate,
                           RedirectAttributes ra) {
        Store s = storeService.get(storeId);
        storeService.checkAccess(currentUser.get(), s);
        if (medicineId == null || medicineId.isEmpty()) throw new BusinessException("Search for a product and choose it to add.");
        List<StoreCatalogService.NewListing> listings = new java.util.ArrayList<>();
        for (int k = 0; k < medicineId.size(); k++) {
            listings.add(new StoreCatalogService.NewListing(medicineId.get(k), parseStock(at(stock, k)), parseDate(at(expiryDate, k))));
        }
        int added = storeCatalog.listAll(s, listings);
        ra.addFlashAttribute("success", (added == 1 ? "1 product" : added + " products") + " added to " + s.getName()
                + "'s catalogue. Set the stock and expiry of any you left empty below.");
        return back(s);
    }

    private static String at(List<String> values, int k) { return values != null && k < values.size() ? values.get(k) : null; }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException("Enter the batch expiry as a date.");
        }
    }

    private static int parseStock(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("Enter the stock as a whole number of packs.");
        }
    }

    /** Starts selling a master product in a store (or updates it if the store already lists it). */
    @PostMapping("/listings")
    public String list(@RequestParam Long storeId, @RequestParam Long medicineId,
                       @RequestParam(required = false) BigDecimal price, @RequestParam(defaultValue = "0") int stock,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
                       RedirectAttributes ra) {
        Store s = storeService.get(storeId);
        storeService.checkAccess(currentUser.get(), s);
        StoreItem i = storeCatalog.list(s, medicineId, price, stock, expiryDate);
        ra.addFlashAttribute("success", i.getMedicine().getName() + " is now in " + s.getName() + "'s catalogue.");
        return back(s);
    }

    @PostMapping("/listings/{itemId}")
    public String updateListing(@PathVariable Long itemId, @RequestParam(required = false) BigDecimal price,
                                @RequestParam int stock,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
                                RedirectAttributes ra) {
        StoreItem i = checked(itemId);
        storeCatalog.update(itemId, price, stock, expiryDate);
        ra.addFlashAttribute("success", i.getMedicine().getName() + " updated.");
        return back(i.getStore());
    }

    @PostMapping("/listings/{itemId}/active")
    public String setListingActive(@PathVariable Long itemId, @RequestParam boolean active, RedirectAttributes ra) {
        StoreItem i = checked(itemId);
        storeCatalog.setActive(itemId, active);
        ra.addFlashAttribute("success", i.getMedicine().getName() + (active ? " is on sale again." : " is hidden from this store's catalogue."));
        return back(i.getStore());
    }

    private StoreItem checked(Long itemId) {
        StoreItem i = storeCatalog.item(itemId);
        storeService.checkAccess(currentUser.get(), i.getStore());
        return i;
    }

    private String back(Store s) {
        return "redirect:/admin/medicines" + (currentUser.get().isAdmin() ? "?store=" + s.getId() : "");
    }
}
