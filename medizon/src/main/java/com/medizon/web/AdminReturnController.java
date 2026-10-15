package com.medizon.web;

import com.medizon.domain.ReturnRequest;
import com.medizon.domain.ReturnStatus;
import com.medizon.domain.Store;
import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.ReturnService;
import com.medizon.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Return requests: approve or reject, then mark picked up and refunded. A store account handles its own store's
 * returns; the admin handles every store's.
 */
@Controller
@RequestMapping("/admin/returns")
@RequiredArgsConstructor
public class AdminReturnController {

    private final ReturnService returnService;
    private final StoreService storeService;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(@RequestParam(required = false) ReturnStatus status, Model model) {
        Store scope = storeService.scopeOf(currentUser.get()).orElse(null);
        model.addAttribute("returns", returnService.adminList(status, scope));
        model.addAttribute("counts", returnService.adminCounts(scope));
        model.addAttribute("statuses", ReturnStatus.values());
        model.addAttribute("selected", status);
        model.addAttribute("active", "returns");
        return "admin/returns";
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

    /** The return, if it belongs to a store this account handles. */
    private ReturnRequest checked(Long id) {
        User me = currentUser.get();
        ReturnRequest r = returnService.get(id);
        storeService.checkAccess(me, r.getOrder());
        return r;
    }
}
