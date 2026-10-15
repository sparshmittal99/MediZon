package com.medizon.web;

import com.medizon.domain.Store;
import com.medizon.domain.StoreArea;
import com.medizon.service.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

/** Admin > Stores: the stores, their details for invoices, the PIN codes each serves, and their accounts. */
@Controller
@RequestMapping("/admin/stores")
@RequiredArgsConstructor
public class StoreAdminController {

    private final StoreService storeService;

    @GetMapping
    public String list(Model model) {
        Map<Long, Long> listings = new HashMap<>(), areas = new HashMap<>(), staff = new HashMap<>();
        for (Store s : storeService.all()) {
            listings.put(s.getId(), storeService.listingCount(s));
            areas.put(s.getId(), (long) storeService.areas(s).size());
            staff.put(s.getId(), (long) storeService.staff(s).size());
        }
        model.addAttribute("stores", storeService.all());
        model.addAttribute("listings", listings);
        model.addAttribute("areaCounts", areas);
        model.addAttribute("staffCounts", staff);
        model.addAttribute("active", "stores");
        return "admin/stores";
    }

    @GetMapping("/new")
    public String newStore(Model model) {
        model.addAttribute("storeForm", new Store());
        model.addAttribute("active", "stores");
        return "admin/store-form";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Store s = storeService.get(id);
        model.addAttribute("storeForm", s);
        model.addAttribute("areas", storeService.areas(s));
        model.addAttribute("staff", storeService.staff(s));
        model.addAttribute("listingCount", storeService.listingCount(s));
        model.addAttribute("active", "stores");
        return "admin/store-form";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("storeForm") Store form, BindingResult result, Model model, RedirectAttributes ra) {
        if (result.hasErrors()) {
            if (form.getId() != null) {
                Store s = storeService.get(form.getId());
                model.addAttribute("areas", storeService.areas(s));
                model.addAttribute("staff", storeService.staff(s));
                model.addAttribute("listingCount", storeService.listingCount(s));
            }
            model.addAttribute("active", "stores");
            return "admin/store-form";
        }
        Store s = storeService.save(form);
        ra.addFlashAttribute("success", s.getName() + " saved." + (form.getId() == null
                ? " Next: choose the PIN codes it delivers to, and create its store account under Users." : ""));
        return "redirect:/admin/stores/" + s.getId();
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable Long id, @RequestParam boolean active, RedirectAttributes ra) {
        Store s = storeService.setActive(id, active);
        ra.addFlashAttribute("success", active ? s.getName() + " is open again." : s.getName() + " is paused: it sells nothing and delivers nowhere until reopened.");
        return "redirect:/admin/stores/" + id;
    }

    /** The store delivers to these PIN codes in the given time. */
    @PostMapping("/{id}/areas")
    public String serve(@PathVariable Long id, @RequestParam String pincodes, @RequestParam int minDays,
                        @RequestParam int maxDays, RedirectAttributes ra) {
        int n = storeService.serve(id, pincodes, minDays, maxDays);
        ra.addFlashAttribute("success", n == 1 ? "PIN code saved." : n + " PIN codes saved.");
        return "redirect:/admin/stores/" + id + "#areas";
    }

    @PostMapping("/{id}/areas/{areaId}")
    public String setDays(@PathVariable Long id, @PathVariable Long areaId, @RequestParam int minDays,
                          @RequestParam int maxDays, RedirectAttributes ra) {
        StoreArea a = storeService.setDays(areaId, minDays, maxDays);
        ra.addFlashAttribute("success", a.getPincode() + ": " + a.getDaysLabel().toLowerCase() + ".");
        return "redirect:/admin/stores/" + id + "#areas";
    }

    @PostMapping("/{id}/areas/{areaId}/delete")
    public String stopServing(@PathVariable Long id, @PathVariable Long areaId, RedirectAttributes ra) {
        StoreArea a = storeService.stopServing(areaId);
        ra.addFlashAttribute("success", "No longer delivering to " + a.getPincode() + ".");
        return "redirect:/admin/stores/" + id + "#areas";
    }
}
