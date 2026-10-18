package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.service.BusinessException;
import com.medizon.service.CurrentUser;
import com.medizon.service.StockAlertService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * "Notify me" without JavaScript, and for visitors: their button leads through sign-in to {@link #afterSignIn}, which
 * turns the alert on and goes back to the product.
 */
@Controller
@RequestMapping("/stock-alerts")
@RequiredArgsConstructor
public class StockAlertController {

    private static final String ON = "We'll notify you when it's back in stock.";

    private final StockAlertService stockAlerts;
    private final CurrentUser currentUser;
    private final DeliveryLocations deliveryLocations;

    @PostMapping("/{medicineId}")
    public String set(@PathVariable Long medicineId, @RequestParam(defaultValue = "true") boolean on,
                      @RequestHeader(value = "Referer", required = false) String referer,
                      HttpServletRequest request, RedirectAttributes ra) {
        User u = currentUser.get();
        if (on) stockAlerts.subscribe(u, medicineId, deliveryLocations.pincode(request));
        else stockAlerts.unsubscribe(u, medicineId);
        ra.addFlashAttribute("success", on ? ON : "Back-in-stock alert turned off.");
        return "redirect:" + (referer != null ? referer : "/medicines/" + medicineId);
    }

    @GetMapping("/{medicineId}/sign-in")
    public String afterSignIn(@PathVariable Long medicineId, HttpServletRequest request, RedirectAttributes ra) {
        try {
            stockAlerts.subscribe(currentUser.get(), medicineId, deliveryLocations.pincode(request));
            ra.addFlashAttribute("success", ON);
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());     // back in stock meanwhile, or no longer sold
        }
        return "redirect:/medicines/" + medicineId;
    }
}
