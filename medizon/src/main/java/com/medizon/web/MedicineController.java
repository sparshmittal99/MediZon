package com.medizon.web;

import com.medizon.domain.Medicine;
import com.medizon.dto.Product;
import com.medizon.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The shop. Shoppers see what the stores delivering to their location sell, priced by the nearest store that has it;
 * without a location (or where nobody delivers yet), everything any store sells.
 */
@Controller
@RequiredArgsConstructor
public class MedicineController {

    private final MedicineService medicineService;
    private final CatalogService catalog;
    private final DeliveryLocations deliveryLocations;
    private final CurrentUser currentUser;
    private final MedicationPlanService planService;

    @GetMapping("/medicines")
    public String list(@RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "") String category,
                       @RequestParam(defaultValue = "0") int page,
                       HttpServletRequest request, Model model) {
        String pin = deliveryLocations.pincode(request);
        model.addAttribute("results", catalog.search(q, category, page, pin));
        model.addAttribute("categories", catalog.categories(pin));
        model.addAttribute("q", q);
        model.addAttribute("category", category);
        return "medicines/list";
    }

    @GetMapping("/medicines/{id}")
    public String detail(@PathVariable Long id, Model model, HttpServletRequest request) {
        Medicine m = medicineService.get(id);
        if (!m.isActive()) throw new BusinessException(m.getName() + " is no longer sold.");
        String pin = deliveryLocations.pincode(request);
        Product p = catalog.product(m, pin);
        model.addAttribute("m", p);
        model.addAttribute("returnDays", ReturnService.RETURN_WINDOW_DAYS);
        // When it would arrive, from the store that would deliver it
        if (p.isInStock()) catalog.estimate(p.getOffer()).ifPresent(e -> model.addAttribute("delivery", e));
        // Dose reminders are offered once the medicine has been delivered for someone in the household (not "Others")
        currentUser.find().filter(u -> !u.isStaff())
                .flatMap(u -> planService.suggestedMember(u, m.getId()))
                .ifPresent(memberId -> model.addAttribute("reminderMemberId", memberId));
        return "medicines/detail";
    }
}
