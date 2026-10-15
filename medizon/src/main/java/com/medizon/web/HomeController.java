package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.MedicationPlanService;
import com.medizon.service.CatalogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final CatalogService catalog;
    private final DeliveryLocations deliveryLocations;
    private final MedicationPlanService planService;
    private final CurrentUser currentUser;

    @GetMapping("/")
    public String home(Model model, HttpServletRequest request) {
        if (currentUser.find().map(User::isStaff).orElse(false)) return "redirect:/admin";
        // What the stores delivering to the shopper sell; everything on sale anywhere until they pick a location
        String pin = deliveryLocations.pincode(request);
        model.addAttribute("featured", catalog.featured(pin));
        model.addAttribute("deals", catalog.deals(4, pin));
        model.addAttribute("shelves", catalog.shelves(5, 4, pin));
        currentUser.find().ifPresent(u -> {
            model.addAttribute("doses", planService.todaysDoses(u));
            model.addAttribute("lowPlans", planService.forUser(u).stream()
                    .filter(p -> p.isActive() && p.isRunningLow()).toList());
        });
        return "index";
    }
}
