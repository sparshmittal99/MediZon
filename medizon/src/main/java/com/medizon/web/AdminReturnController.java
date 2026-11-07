package com.medizon.web;

import com.medizon.domain.Order;
import com.medizon.domain.ReturnReason;
import com.medizon.domain.ReturnRequest;
import com.medizon.domain.ReturnSettings;
import com.medizon.domain.ReturnStatus;
import com.medizon.domain.Store;
import com.medizon.domain.User;
import com.medizon.dto.PeriodFilter;
import com.medizon.dto.ReturnPickupForm;
import com.medizon.service.OrderService;
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
    private final OrderService orderService;

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
        ReturnRequest r = checked(id);
        model.addAttribute("r", r);
        // A rejected or cancelled return can be followed by one staff raise themselves
        model.addAttribute("canRaise", r.getStatus().isClosedWithoutReturn() && returnService.staffBlocker(r.getOrder()).isEmpty());
        model.addAttribute("active", "returns");
        return "admin/return-detail";
    }

    // ------------------------------------------------------------------ raised by staff, e.g. after a support ticket

    @GetMapping("/new")
    public String raiseForm(@RequestParam("order") Long orderId, Model model, RedirectAttributes ra) {
        Order o = orderService.get(orderId);
        storeService.checkAccess(currentUser.get(), o);
        String blocker = returnService.staffBlocker(o).orElse(null);
        if (blocker != null) {
            ra.addFlashAttribute("error", blocker);
            return "redirect:/admin/orders/" + o.getId();
        }
        model.addAttribute("o", o);
        model.addAttribute("left", returnService.staffReturnable(o));
        model.addAttribute("reasons", ReturnReason.values());
        model.addAttribute("pickupDay", returnService.pickupDate());
        model.addAttribute("previous", returnService.forOrder(o));
        model.addAttribute("active", "returns");
        return "admin/return-new";
    }

    @PostMapping("/new")
    public String raise(@RequestParam("order") Long orderId,
                        @RequestParam(required = false) List<Long> itemId, @RequestParam(required = false) List<Integer> qty,
                        @RequestParam(required = false) ReturnReason reason,
                        @RequestParam(required = false) String customerNote, @RequestParam(required = false) String staffNote,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate pickupDay,
                        @ModelAttribute ReturnPickupForm refund, RedirectAttributes ra) {
        Order o = orderService.get(orderId);
        storeService.checkAccess(currentUser.get(), o);
        ReturnRequest r = returnService.raiseForCustomer(currentUser.get(), orderId, itemId, qty, reason, customerNote,
                staffNote, pickupDay, refund);
        ra.addFlashAttribute("success", "Return " + r.getReturnNumber() + " raised and approved. "
                + r.getUser().getFullName() + " has been told about the pickup.");
        return "redirect:/admin/returns/" + r.getId();
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        checked(id);
        ReturnRequest r = returnService.cancel(id, currentUser.get(), reason);
        ra.addFlashAttribute("success", "Return " + r.getReturnNumber() + " cancelled. The customer has been told.");
        return "redirect:/admin/returns/" + id;
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
