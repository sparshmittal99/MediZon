package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.dto.Product;
import com.medizon.service.CurrentUser;
import com.medizon.service.MedicationPlanService;
import com.medizon.service.CatalogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

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
        model.addAttribute("showCatbar", true);     // the category bar is for the front page only
        // Only what can be ordered now; out-of-stock products stay in the shop with a "Notify me" button
        List<Product> inStock = catalog.inStock(pin);
        model.addAttribute("featured", CatalogService.featured(inStock));
        model.addAttribute("deals", CatalogService.deals(inStock, 4));
        model.addAttribute("shelves", CatalogService.shelves(inStock, 5, 4));
        currentUser.find().ifPresent(u -> {
            model.addAttribute("doses", planService.todaysDoses(u));
            model.addAttribute("lowPlans", planService.forUser(u).stream()
                    .filter(p -> p.isActive() && p.isRunningLow()).toList());
        });
        return "index";
    }
}
