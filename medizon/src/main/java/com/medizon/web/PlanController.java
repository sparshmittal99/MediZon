package com.medizon.web;

import com.medizon.domain.*;
import com.medizon.dto.DoseSlot;
import com.medizon.dto.PlanForm;
import com.medizon.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
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
        model.addAttribute("customUnits", PlanForm.CUSTOM_UNITS);
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
                : " " + saved.movedFromCommon() + " " + p.getUnitLabel() + " moved from common medicines.";
        ra.addFlashAttribute("success", (p.isOnCommonShelf() ? p.getMedicineName() + " saved on the common shelf."
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

    /**
     * Dose history of one medicine: when each dose was due, whether it was marked taken or skipped (or never marked),
     * when it was marked, and who takes it. {@code period} picks which doses: the most recent ones (default), one day
     * ({@code date}), one month ({@code month} + {@code year}) or one year; {@code show} narrows them to taken,
     * skipped or missed.
     */
    @GetMapping("/{id}/history")
    public String history(@PathVariable Long id,
                          @RequestParam(defaultValue = "recent") String period,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam(required = false) Integer month,
                          @RequestParam(required = false) Integer year,
                          @RequestParam(required = false) String show,
                          @RequestParam(defaultValue = "0") int page, Model model) {
        MedicationPlan p = planService.get(id, currentUser.get());
        DoseStatus status = switch (show == null ? "" : show) {
            case "taken" -> DoseStatus.TAKEN;
            case "skipped" -> DoseStatus.SKIPPED;
            case "missed" -> DoseStatus.PENDING;
            default -> null;
        };
        show = status == null ? "all" : show;

        LocalDate today = LocalDate.now();
        LocalDate firstDay = planService.firstDoseDay(p);
        int firstYear = firstDay == null ? today.getYear() : Math.min(firstDay.getYear(), today.getYear());
        // The day a period is built around; switching period keeps it (a day's month, a month's year)
        LocalDate anchor = date != null && !date.isAfter(today) ? date : today;
        int y = Math.max(firstYear, Math.min(today.getYear(), year != null ? year : anchor.getYear()));
        int m = Math.max(1, Math.min(12, month != null ? month : anchor.getMonthValue()));

        // The chosen period as an inclusive date range, its label, and a day in the periods before and after it
        LocalDate from, to, prev, next;
        String label, query;
        switch (period) {
            case "day" -> {
                from = to = anchor;
                label = anchor.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH));
                query = "period=day&date=" + anchor;
                prev = anchor.minusDays(1);
                next = anchor.plusDays(1);
            }
            case "month" -> {
                YearMonth ym = YearMonth.of(y, m);
                if (ym.isAfter(YearMonth.from(today))) ym = YearMonth.from(today);
                from = ym.atDay(1);
                to = ym.atEndOfMonth();
                anchor = ym.equals(YearMonth.from(today)) ? today : from;
                label = ym.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
                query = "period=month&year=" + ym.getYear() + "&month=" + ym.getMonthValue();
                prev = from.minusMonths(1);
                next = from.plusMonths(1);
            }
            case "year" -> {
                from = LocalDate.of(y, 1, 1);
                to = LocalDate.of(y, 12, 31);
                anchor = y == today.getYear() ? today : from;
                label = String.valueOf(y);
                query = "period=year&year=" + y;
                prev = from.minusYears(1);
                next = from.plusYears(1);
            }
            default -> {
                period = "recent";
                from = to = prev = next = null;
                label = null;
                query = "period=recent";
            }
        }

        List<DoseLog> logs;
        Map<DoseStatus, Long> counts;
        if (from == null) {             // the most recent doses, counted and filtered here
            List<DoseLog> recent = planService.latestDoses(p);
            counts = new EnumMap<>(DoseStatus.class);
            for (DoseStatus s : DoseStatus.values()) counts.put(s, 0L);
            recent.forEach(l -> counts.merge(l.getStatus(), 1L, Long::sum));
            logs = status == null ? recent : recent.stream().filter(l -> l.getStatus() == status).toList();
        } else {
            LocalDateTime start = from.atStartOfDay(), end = to.atTime(LocalTime.MAX);
            counts = planService.doseCountsBetween(p, start, end);
            Page<DoseLog> pg = planService.dosesBetween(p, start, end, status, page);
            logs = pg.getContent();
            model.addAttribute("pages", pg);
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        String base = "/plans/" + p.getId() + "/history?";
        String showQuery = status == null ? "" : "&show=" + show;
        Map<String, String> periodLinks = new LinkedHashMap<>();
        periodLinks.put("recent", base + "period=recent" + showQuery);
        periodLinks.put("day", base + "period=day&date=" + anchor + showQuery);
        periodLinks.put("month", base + "period=month&year=" + anchor.getYear() + "&month=" + anchor.getMonthValue() + showQuery);
        periodLinks.put("year", base + "period=year&year=" + anchor.getYear() + showQuery);
        Map<String, String> statusLinks = new LinkedHashMap<>();
        for (String s : List.of("all", "taken", "skipped", "missed")) {
            statusLinks.put(s, base + query + (s.equals("all") ? "" : "&show=" + s));
        }
        // Stepping back stops at the period holding the first logged dose; forward stops at today's
        boolean hasPrev = prev != null && firstDay != null && from.isAfter(firstDay);
        boolean hasNext = next != null && to.isBefore(today);

        Map<Integer, String> months = new LinkedHashMap<>();
        int pickYear = from == null ? anchor.getYear() : from.getYear();
        for (Month mo : Month.values()) months.put(mo.getValue(), mo.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        List<Integer> years = new ArrayList<>();
        for (int yr = today.getYear(); yr >= firstYear; yr--) years.add(yr);

        model.addAttribute("plan", p);
        model.addAttribute("period", period);
        model.addAttribute("filtered", !period.equals("recent") || status != null);
        if (period.equals("recent") && status == null) {
            planService.missedPattern(p).ifPresent(mp -> model.addAttribute("missedHint", mp));
        }
        model.addAttribute("periodLabel", label);
        model.addAttribute("show", show);
        model.addAttribute("logs", logs);
        model.addAttribute("counts", counts);
        model.addAttribute("total", total);
        model.addAttribute("takenPercent", total == 0 ? null : Math.round(counts.get(DoseStatus.TAKEN) * 100.0 / total));
        model.addAttribute("hasDoses", firstDay != null);
        model.addAttribute("periodLinks", periodLinks);
        model.addAttribute("statusLinks", statusLinks);
        model.addAttribute("pageLink", base + query + showQuery + "&page=");
        model.addAttribute("prevLink", hasPrev ? periodLink(base, period, prev) + showQuery : null);
        model.addAttribute("nextLink", hasNext ? periodLink(base, period, next) + showQuery : null);
        model.addAttribute("pickDate", anchor);
        model.addAttribute("pickMonth", from == null ? anchor.getMonthValue() : from.getMonthValue());
        model.addAttribute("pickYear", pickYear);
        model.addAttribute("minDate", firstDay);
        model.addAttribute("today", today);
        model.addAttribute("months", months);
        model.addAttribute("years", years);
        return "plans/history";
    }

    private static String periodLink(String base, String period, LocalDate d) {
        return base + switch (period) {
            case "day" -> "period=day&date=" + d;
            case "month" -> "period=month&year=" + d.getYear() + "&month=" + d.getMonthValue();
            default -> "period=year&year=" + d.getYear();
        };
    }

    /** Manual refill: puts the plan's usual quantity in the cart, for the same member. */
    @PostMapping("/{id}/reorder")
    public String reorder(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        User user = currentUser.get();
        MedicationPlan p = planService.get(id, user);
        if (p.isCustom()) throw new BusinessException(p.getMedicineName() + " isn't sold on MediZon, so it can't be reordered here.");
        cartService.add(user, p.getMedicine().getId(), p.getReorderPacks(), List.of(p.getMember()), deliveryLocations.pincode(request));
        ra.addFlashAttribute("success", p.getReorderPacks() + " pack(s) of " + p.getTitle() + " added. Check out to place the refill.");
        return "redirect:/cart";
    }
}
