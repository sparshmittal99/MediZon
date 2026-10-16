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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;

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

    /** The image is chosen through upload / link fields, never posted directly. */
    @InitBinder("medicine")
    void medicineBinder(WebDataBinder binder) { binder.setDisallowedFields("imageUrl"); }

    /** A store account sees its own catalogue; the admin the master catalogue, or one store's with {@code store}. */
    @GetMapping
    public String list(@RequestParam(required = false) Long store, Model model) {
        User me = currentUser.get();
        Store s = storeService.scopeOf(me).orElse(store == null ? null : storeService.get(store));
        model.addAttribute("active", "medicines");
        model.addAttribute("stores", me.isAdmin() ? storeService.all() : java.util.List.of());
        if (s == null) {
            model.addAttribute("medicines", medicineService.all());
            model.addAttribute("coverage", storeCatalog.coverage());
            return "admin/medicines";
        }
        model.addAttribute("store", s);
        model.addAttribute("items", storeCatalog.items(s));
        model.addAttribute("notListed", storeCatalog.notListed(s));
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

    // ---------------------------------------------------------------- a store's listings

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
