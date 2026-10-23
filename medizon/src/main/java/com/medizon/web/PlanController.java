package com.medizon.web;

import com.medizon.domain.*;
import com.medizon.dto.DoseSlot;
import com.medizon.dto.PlanForm;
import com.medizon.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/** "My medicines": dose reminders, supply at home and refill settings, grouped by family member. */
@Controller
@RequestMapping("/plans")
@RequiredArgsConstructor
public class PlanController {

    private final MedicationPlanService planService;
    private final ReorderService reorderService;
    private final AddressService addressService;
    private final MedicineService medicineService;
    private final CartService cartService;
    private final MemberService memberService;
    private final CurrentUser currentUser;
    private final DeliveryLocations deliveryLocations;
    private final CatalogService catalog;

    /** {@code member} narrows the page to one person (or the common shelf); without it every member gets a section. */
    @GetMapping
    public String list(@RequestParam(required = false) Long member, Model model) {
        User user = currentUser.get();
        List<Member> all = memberService.all(user);
        Member selected = member == null ? null : memberService.get(member, user);

        List<MedicationPlan> plans = planService.forUser(user);
        Map<Long, List<MedicationPlan>> byMember = plans.stream().filter(p -> !p.isSetupRequired())
                .collect(Collectors.groupingBy(p -> p.getMember().getId(), LinkedHashMap::new, Collectors.toList()));
        List<MedicationPlan> needSetup = plans.stream().filter(MedicationPlan::isSetupRequired)
                .filter(p -> selected == null || p.getMember().getId().equals(selected.getId())).toList();
        List<DoseSlot> doses = planService.todaysDoses(user).stream()
                .filter(d -> selected == null || d.getPlan().getMember().getId().equals(selected.getId())).toList();

        // Sections to show: the chosen member, or everyone with medicines (the account holder always, as the empty state)
        List<Member> sections = selected != null ? List.of(selected) : all.stream()
                .filter(m -> m.isSelf() || byMember.containsKey(m.getId())).toList();

        model.addAttribute("members", all);
        model.addAttribute("selected", selected);
        model.addAttribute("sections", sections);
        model.addAttribute("plansByMember", byMember);
        model.addAttribute("needSetup", needSetup);
        model.addAttribute("doses", doses);
        model.addAttribute("hasFamily", all.size() > 2);
        model.addAttribute("planCount", plans.stream().collect(Collectors.groupingBy(p -> p.getMember().getId(), Collectors.counting())));
        return "plans/list";
    }

    @GetMapping("/new")
    public String newPlan(@RequestParam(required = false) Long medicineId, @RequestParam(required = false) Long memberId,
                          HttpServletRequest request, Model model) {
        User user = currentUser.get();
        PlanForm form = new PlanForm();
        form.setMemberId(memberId != null ? memberService.get(memberId, user).getId()
                : medicineId != null ? planService.suggestedMember(user, medicineId).orElse(memberService.self(user).getId())
                : memberService.self(user).getId());
        if (medicineId != null) {
            Medicine m = medicineService.get(medicineId);
            form.setMedicineId(m.getId());
            // Medicines already on the common shelf are moved across on save, so start from what the person has of their own
            boolean onShelf = planService.shelfStock(user).getOrDefault(m.getId(), 0) > 0;
            form.setUnitsRemaining(onShelf ? 0 : m.getUnitCount());
            if (!onShelf) form.setExpiryDate(catalog.offer(m, deliveryLocations.pincode(request), 1).expiryDate());
        }
        return showForm(form, model);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        return showForm(PlanForm.from(planService.get(id, currentUser.get())), model);
    }

    private String showForm(PlanForm form, Model model) {
        model.addAttribute("form", form);
        addFormOptions(model, form);
        return "plans/form";
    }

    /**
     * The medicine picker only offers what reached the household: medicines delivered for the chosen person, plus the
     * common (OTC) medicines. Each option lists who may take it ({@code data-members}); the page narrows the list
     * when "Taken by" changes. A plan being edited keeps its own medicine.
     */
    private void addFormOptions(Model model, PlanForm form) {
        User user = currentUser.get();
        Map<Long, Set<Long>> delivered = planService.deliveredFor(user);
        Long commonId = memberService.common(user).getId();
        List<Member> members = memberService.all(user);
        List<Medicine> medicines = medicineService.allActive().stream()
                .filter(m -> delivered.containsKey(m.getId()) || m.getId().equals(form.getMedicineId())).toList();
        Map<Long, String> takers = new HashMap<>();
        for (Medicine m : medicines) {
            takers.put(m.getId(), members.stream()
                    .filter(who -> MedicationPlanService.canTrack(who, m.getId(), delivered, commonId)
                            || (m.getId().equals(form.getMedicineId()) && who.getId().equals(form.getMemberId())))
                    .map(who -> who.getId().toString()).collect(Collectors.joining(" ")));
        }
        model.addAttribute("medicines", medicines);
        model.addAttribute("medicineTakers", takers);
        model.addAttribute("members", members);
        model.addAttribute("reorderModes", ReorderMode.values());
        model.addAttribute("scheduleTypes", ScheduleType.values());
        model.addAttribute("weekDays", DayOfWeek.values());
        model.addAttribute("shelfStock", planService.shelfStock(currentUser.get()));
        model.addAttribute("addresses", addressService.list(currentUser.get()));
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("form") PlanForm form, BindingResult result, Model model,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            while (form.getTimes().size() < 4) form.getTimes().add("");
            addFormOptions(model, form);
            return "plans/form";
        }
        MedicationPlanService.Saved saved = planService.saveReporting(currentUser.get(), form);
        MedicationPlan p = saved.plan();
        reorderService.evaluate(p.getId());
        String moved = saved.movedFromCommon() == 0 ? ""
                : " " + saved.movedFromCommon() + " " + p.getMedicine().getUnitLabel() + " moved from common medicines.";
        ra.addFlashAttribute("success", (p.isOnCommonShelf() ? p.getMedicine().getName() + " saved on the common shelf."
                : "Reminders saved for " + p.getTitle() + ".") + moved);
        return "redirect:/plans?member=" + p.getMember().getId();
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, @RequestHeader(value = "Referer", required = false) String referer,
                         RedirectAttributes ra) {
        MedicationPlan p = planService.toggleActive(id, currentUser.get());
        ra.addFlashAttribute("success", p.isActive() ? "Reminders resumed." : "Reminders paused.");
        return "redirect:" + (referer != null ? referer : "/plans");
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, @RequestHeader(value = "Referer", required = false) String referer,
                         RedirectAttributes ra) {
        planService.delete(id, currentUser.get());
        ra.addFlashAttribute("success", "Medicine removed from the list.");
        return "redirect:" + (referer != null ? referer : "/plans");
    }

    @PostMapping("/{id}/dose")
    public String dose(@PathVariable Long id,
                       @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime time,
                       @RequestParam DoseStatus status,
                       @RequestParam(defaultValue = "/plans") String returnTo) {
        MedicationPlan p = planService.respond(currentUser.get(), id, time, status);
        reorderService.evaluate(p.getId());
        return "redirect:" + (returnTo.startsWith("/") && !returnTo.startsWith("//") ? returnTo : "/plans");
    }

    /** Manual refill: puts the plan's usual quantity in the cart, for the same member. */
    @PostMapping("/{id}/reorder")
    public String reorder(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        User user = currentUser.get();
        MedicationPlan p = planService.get(id, user);
        cartService.add(user, p.getMedicine().getId(), p.getReorderPacks(), List.of(p.getMember()), deliveryLocations.pincode(request));
        ra.addFlashAttribute("success", p.getReorderPacks() + " pack(s) of " + p.getTitle() + " added. Check out to place the refill.");
        return "redirect:/cart";
    }
}
