package com.medizon.web;

import com.medizon.domain.ReturnRequest;
import com.medizon.domain.ReturnStatus;
import com.medizon.service.ReturnService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Return requests: approve or reject, then mark picked up and refunded. */
@Controller
@RequestMapping("/admin/returns")
@RequiredArgsConstructor
public class AdminReturnController {

    private final ReturnService returnService;

    @GetMapping
    public String list(@RequestParam(required = false) ReturnStatus status, Model model) {
        model.addAttribute("returns", returnService.adminList(status));
        model.addAttribute("counts", returnService.adminCounts());
        model.addAttribute("statuses", ReturnStatus.values());
        model.addAttribute("selected", status);
        model.addAttribute("active", "returns");
        return "admin/returns";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("r", returnService.get(id));
        model.addAttribute("active", "returns");
        return "admin/return-detail";
    }

    @PostMapping("/{id}/decision")
    public String decide(@PathVariable Long id, @RequestParam boolean approve,
                         @RequestParam(required = false) String note, RedirectAttributes ra) {
        ReturnRequest r = returnService.decide(id, approve, note);
        ra.addFlashAttribute("success", "Return " + r.getReturnNumber() + (approve ? " approved." : " rejected."));
        return "redirect:/admin/returns/" + id;
    }

    @PostMapping("/{id}/picked-up")
    public String pickedUp(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean restock,
                           RedirectAttributes ra) {
        ReturnRequest r = returnService.markPickedUp(id, restock);
        ra.addFlashAttribute("success", "Return " + r.getReturnNumber() + " marked picked up"
                + (restock ? "; packs added back to stock." : "."));
        return "redirect:/admin/returns/" + id;
    }

    @PostMapping("/{id}/refund")
    public String refund(@PathVariable Long id, RedirectAttributes ra) {
        ReturnRequest r = returnService.refund(id);
        ra.addFlashAttribute("success", "₹" + r.getRefundAmount() + " refunded for return " + r.getReturnNumber() + ".");
        return "redirect:/admin/returns/" + id;
    }
}
