package com.medizon.web;

import com.medizon.dto.BillingForm;
import com.medizon.service.CurrentUser;
import com.medizon.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The account's single billing address, used on invoices only. */
@Controller
@RequestMapping("/billing-address")
@RequiredArgsConstructor
public class BillingController {

    private final UserService userService;
    private final CurrentUser currentUser;

    @GetMapping
    public String view(@RequestParam(required = false) String returnTo, Model model) {
        model.addAttribute("form", BillingForm.from(currentUser.get()));
        model.addAttribute("fromCheckout", "checkout".equals(returnTo));
        return "billing";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("form") BillingForm form, BindingResult result,
                       @RequestParam(required = false) String returnTo, Model model, RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute("fromCheckout", "checkout".equals(returnTo));
            return "billing";
        }
        userService.saveBillingAddress(currentUser.get(), form);
        ra.addFlashAttribute("success", "Billing address saved. Invoices from now on are billed to it.");
        return "checkout".equals(returnTo) ? "redirect:/checkout" : "redirect:/billing-address";
    }

    @PostMapping("/remove")
    public String remove(RedirectAttributes ra) {
        userService.removeBillingAddress(currentUser.get());
        ra.addFlashAttribute("success", "Billing address removed. Orders are billed to the address they're delivered to.");
        return "redirect:/billing-address";
    }
}
