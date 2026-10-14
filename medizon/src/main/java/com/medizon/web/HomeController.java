package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.MedicationPlanService;
import com.medizon.service.MedicineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final MedicineService medicineService;
    private final MedicationPlanService planService;
    private final CurrentUser currentUser;

    @GetMapping("/")
    public String home(Model model) {
        if (currentUser.find().map(User::isAdmin).orElse(false)) return "redirect:/admin";
        model.addAttribute("featured", medicineService.featured());
        model.addAttribute("deals", medicineService.deals(4));
        model.addAttribute("shelves", medicineService.shelves(5, 4));
        currentUser.find().ifPresent(u -> {
            model.addAttribute("doses", planService.todaysDoses(u));
            model.addAttribute("lowPlans", planService.forUser(u).stream()
                    .filter(p -> p.isActive() && p.isRunningLow()).toList());
        });
        return "index";
    }
}
