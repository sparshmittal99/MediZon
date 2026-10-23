package com.medizon.web;

import com.medizon.service.BusinessException;
import com.medizon.service.CurrentUser;
import com.medizon.service.OrderSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/** The admin's order settings: for now, the minimum order value customers' carts must reach (admin only, see SecurityConfig). */
@Controller
@RequestMapping("/admin/order-settings")
@RequiredArgsConstructor
public class AdminOrderSettingsController {

    private final OrderSettingsService orderSettings;
    private final CurrentUser currentUser;

    @GetMapping
    public String settings(Model model) {
        model.addAttribute("settings", orderSettings.get());
        model.addAttribute("maxMinOrder", OrderSettingsService.MAX_MIN_ORDER_VALUE);
        model.addAttribute("active", "orders");
        return "admin/order-settings";
    }

    @PostMapping
    public String save(@RequestParam(required = false) BigDecimal minOrderValue, RedirectAttributes ra) {
        try {
            BigDecimal saved = orderSettings.save(minOrderValue, currentUser.get().getEmail()).getMinOrderValue();
            ra.addFlashAttribute("success", saved.signum() == 0
                    ? "Minimum order value removed: carts of any amount can be checked out."
                    : "Minimum order value set to ₹" + OrderSettingsService.plain(saved) + ". Carts below it can't be checked out.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/order-settings";
    }
}
