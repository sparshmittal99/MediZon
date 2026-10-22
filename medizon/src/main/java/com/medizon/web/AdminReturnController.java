package com.medizon.web;

import com.medizon.domain.ReturnRequest;
import com.medizon.domain.ReturnSettings;
import com.medizon.domain.ReturnStatus;
import com.medizon.domain.Store;
import com.medizon.domain.User;
import com.medizon.dto.PeriodFilter;
import com.medizon.service.BusinessException;
import com.medizon.service.CurrentUser;
import com.medizon.service.ReturnService;
import com.medizon.service.ReturnSettingsService;
import com.medizon.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Return requests: approve or reject, then mark picked up and refunded. A store account handles its own store's
 * returns; the admin handles every store's, and sets the return window, pickup range and refund timing.
 */
@Controller
@RequestMapping("/admin/returns")
@RequiredArgsConstructor
public class AdminReturnController {

    private final ReturnService returnService;
    private final StoreService storeService;
    private final ReturnSettingsService returnSettings;
    private final CurrentUser currentUser;

    /** The newest 20 returns requested in the chosen years, months or days (this year by default), or found by number. */
    @GetMapping
    public String list(@RequestParam(required = false) ReturnStatus status,
                       @RequestParam(required = false) List<String> year, @RequestParam(required = false) List<String> month,
                       @RequestParam(required = false) List<String> date, @RequestParam(required = false) String q,
                       Model model) {
        Store scope = storeService.scopeOf(currentUser.get()).orElse(null);
        PeriodFilter period = PeriodFilter.of(year, month, date);
        model.addAttribute("returns", returnService.adminList(status, scope, period, q));
        model.addAttribute("counts", returnService.adminCounts(scope, period, q));
        model.addAttribute("statuses", ReturnStatus.values());
        model.addAttribute("selected", status);
        model.addAttribute("period", period);
        model.addAttribute("periodYears", period.yearsSince(returnService.firstReturnYear()));
        model.addAttribute("q", PeriodFilter.term(q));
        model.addAttribute("limit", PeriodFilter.LIMIT);
        model.addAttribute("active", "returns");
        return "admin/returns";
    }

    // ------------------------------------------------------------------ settings (admin only, see SecurityConfig)

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("settings", returnSettings.get());
        model.addAttribute("active", "returns");
        return "admin/settings";
    }

    @PostMapping("/settings")
    public String saveSettings(@ModelAttribute("settings") ReturnSettings form, Model model, RedirectAttributes ra) {
        try {
            returnSettings.save(form, currentUser.get().getEmail());
            ra.addFlashAttribute("success", "Return settings saved. New returns use them; ones already requested keep what they were promised.");
            return "redirect:/admin/returns/settings";
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("active", "returns");
            return "admin/settings";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("r", checked(id));
        model.addAttribute("active", "returns");
        return "admin/return-detail";
    }

    @PostMapping("/{id}/decision")
    public String decide(@PathVariable Long id, @RequestParam boolean approve,
                         @RequestParam(required = false) String note, RedirectAttributes ra) {
        checked(id);
        ReturnRequest r = returnService.decide(id, approve, note);
        ra.addFlashAttribute("success", "Return " + r.getReturnNumber() + (approve ? " approved." : " rejected."));
        return "redirect:/admin/returns/" + id;
    }

    @PostMapping("/{id}/picked-up")
    public String pickedUp(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean restock,
                           RedirectAttributes ra) {
        checked(id);
        ReturnRequest r = returnService.markPickedUp(id, restock);
        ra.addFlashAttribute("success", "Return " + r.getReturnNumber() + " marked picked up"
                + (restock ? "; packs added back to " + r.getOrder().getStore().getName() + "'s stock." : "."));
        return "redirect:/admin/returns/" + id;
    }

    @PostMapping("/{id}/refund")
    public String refund(@PathVariable Long id, RedirectAttributes ra) {
        checked(id);
        ReturnRequest r = returnService.refund(id);
        ra.addFlashAttribute("success", "₹" + r.getRefundAmount() + " refunded for return " + r.getReturnNumber() + ".");
        return "redirect:/admin/returns/" + id;
    }

    @PostMapping("/{id}/pickup-date")
    public String pickupDate(@PathVariable Long id,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                             RedirectAttributes ra) {
        checked(id);
        ReturnRequest r = returnService.reschedulePickup(id, day);
        ra.addFlashAttribute("success", "Pickup for " + r.getReturnNumber() + " set for "
                + r.getPickupDate().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)) + ". The customer has been told.");
        return "redirect:/admin/returns/" + id;
    }

    /** The return, if it belongs to a store this account handles. */
    private ReturnRequest checked(Long id) {
        User me = currentUser.get();
        ReturnRequest r = returnService.get(id);
        storeService.checkAccess(me, r.getOrder());
        return r;
    }
}
