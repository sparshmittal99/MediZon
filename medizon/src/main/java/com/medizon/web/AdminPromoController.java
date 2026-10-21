package com.medizon.web;

import com.medizon.domain.Coupon;
import com.medizon.domain.ReturnSettings;
import com.medizon.dto.PromoForm;
import com.medizon.service.BusinessException;
import com.medizon.service.CouponService;
import com.medizon.service.CurrentUser;
import com.medizon.service.ReturnSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The admin's promo codes and return settings (admin only, see SecurityConfig). */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminPromoController {

    private final CouponService coupons;
    private final ReturnSettingsService returnSettings;
    private final CurrentUser currentUser;

    // ------------------------------------------------------------------ promos

    @GetMapping("/promos")
    public String promos(Model model) {
        model.addAttribute("promos", coupons.all());
        model.addAttribute("usage", coupons.usage());
        model.addAttribute("active", "promos");
        return "admin/promos";
    }

    @GetMapping("/promos/new")
    public String newPromo(Model model) {
        return form(new PromoForm(), model);
    }

    @GetMapping("/promos/{id}")
    public String editPromo(@PathVariable Long id, Model model) {
        return form(PromoForm.from(coupons.get(id)), model);
    }

    /** A rejected form comes back filled in, with the problem shown above it. */
    @PostMapping("/promos")
    public String savePromo(@ModelAttribute("promo") PromoForm form, Model model, RedirectAttributes ra) {
        try {
            Coupon c = coupons.save(form);
            ra.addFlashAttribute("success", form.getId() == null ? "Promo " + c.getCode() + " added." : "Promo " + c.getCode() + " saved.");
            return "redirect:/admin/promos";
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            return form(form, model);
        }
    }

    @PostMapping("/promos/{id}/active")
    public String setPromoActive(@PathVariable Long id, @RequestParam boolean active, RedirectAttributes ra) {
        Coupon c = coupons.setActive(id, active);
        ra.addFlashAttribute("success", c.getCode() + (active ? " is live again." : " is switched off; customers can't use it."));
        return "redirect:/admin/promos";
    }

    private String form(PromoForm form, Model model) {
        model.addAttribute("promo", form);
        model.addAttribute("kinds", Coupon.Kind.values());
        model.addAttribute("active", "promos");
        return "admin/promo-form";
    }

    // ------------------------------------------------------------------ return settings

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("settings", returnSettings.get());
        model.addAttribute("active", "settings");
        return "admin/settings";
    }

    @PostMapping("/settings")
    public String saveSettings(@ModelAttribute("settings") ReturnSettings form, Model model, RedirectAttributes ra) {
        try {
            returnSettings.save(form, currentUser.get().getEmail());
            ra.addFlashAttribute("success", "Return settings saved. New returns use them; ones already requested keep what they were promised.");
            return "redirect:/admin/settings";
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("active", "settings");
            return "admin/settings";
        }
    }
}
