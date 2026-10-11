package com.medizon.web;

import com.medizon.domain.DoseStatus;
import com.medizon.domain.MedicationPlan;
import com.medizon.domain.Medicine;
import com.medizon.domain.ReorderMode;
import com.medizon.domain.User;
import com.medizon.dto.PlanForm;
import com.medizon.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalTime;

/** "My medicines": dose reminders, supply at home and refill settings. */
@Controller
@RequestMapping("/plans")
@RequiredArgsConstructor
public class PlanController {

    private final MedicationPlanService planService;
    private final ReorderService reorderService;
    private final MedicineService medicineService;
    private final CartService cartService;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(Model model) {
        User user = currentUser.get();
        model.addAttribute("plans", planService.forUser(user));
        model.addAttribute("doses", planService.todaysDoses(user));
        return "plans/list";
    }

    @GetMapping("/new")
    public String newPlan(@RequestParam(required = false) Long medicineId, Model model) {
        PlanForm form = new PlanForm();
        if (medicineId != null) {
            Medicine m = medicineService.get(medicineId);
            form.setMedicineId(m.getId());
            form.setUnitsRemaining(m.getUnitsPerPack());
        }
        return showForm(form, model);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        return showForm(PlanForm.from(planService.get(id, currentUser.get())), model);
    }

    private String showForm(PlanForm form, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("medicines", medicineService.allActive());
        model.addAttribute("reorderModes", ReorderMode.values());
        return "plans/form";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("form") PlanForm form, BindingResult result, Model model,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            while (form.getTimes().size() < 4) form.getTimes().add("");
            model.addAttribute("medicines", medicineService.allActive());
            model.addAttribute("reorderModes", ReorderMode.values());
            return "plans/form";
        }
        MedicationPlan p = planService.save(currentUser.get(), form);
        reorderService.evaluate(p.getId());
        ra.addFlashAttribute("success", "Reminders saved for " + p.getMedicine().getName() + ".");
        return "redirect:/plans";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        MedicationPlan p = planService.toggleActive(id, currentUser.get());
        ra.addFlashAttribute("success", p.isActive() ? "Reminders resumed." : "Reminders paused.");
        return "redirect:/plans";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        planService.delete(id, currentUser.get());
        ra.addFlashAttribute("success", "Medicine removed from your list.");
        return "redirect:/plans";
    }

    @PostMapping("/{id}/dose")
    public String dose(@PathVariable Long id,
                       @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime time,
                       @RequestParam DoseStatus status,
                       @RequestParam(defaultValue = "/plans") String returnTo) {
        MedicationPlan p = planService.respond(currentUser.get(), id, time, status);
        reorderService.evaluate(p.getId());
        return "redirect:" + (returnTo.startsWith("/") ? returnTo : "/plans");
    }

    /** Manual refill: puts the plan's usual quantity in the cart. */
    @PostMapping("/{id}/reorder")
    public String reorder(@PathVariable Long id, RedirectAttributes ra) {
        User user = currentUser.get();
        MedicationPlan p = planService.get(id, user);
        cartService.add(user, p.getMedicine().getId(), p.getReorderPacks());
        ra.addFlashAttribute("success", p.getReorderPacks() + " pack(s) of " + p.getMedicine().getName() + " added. Check out to place the refill.");
        return "redirect:/cart";
    }
}
