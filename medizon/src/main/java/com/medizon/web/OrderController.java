package com.medizon.web;

import com.medizon.domain.Order;
import com.medizon.domain.PaymentMethod;
import com.medizon.domain.User;
import com.medizon.dto.CartSummary;
import com.medizon.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final CartService cartService;
    private final PrescriptionService prescriptionService;
    private final ReviewService reviewService;
    private final CurrentUser currentUser;

    @GetMapping("/checkout")
    public String checkout(Model model) {
        User user = currentUser.get();
        CartSummary cart = cartService.summary(user);
        if (cart.isEmpty()) return "redirect:/cart";
        model.addAttribute("cart", cart);
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("prescriptions", prescriptionService.validForUser(user));
        return "checkout";
    }

    @PostMapping("/checkout")
    public String placeOrder(@RequestParam PaymentMethod paymentMethod,
                             @RequestParam(required = false) Long prescriptionId,
                             RedirectAttributes ra) {
        Order o = orderService.checkout(currentUser.get(), paymentMethod, prescriptionId);
        ra.addFlashAttribute("success", "Order " + o.getOrderNumber() + " placed. We'll notify you as it moves.");
        return "redirect:/orders/" + o.getId();
    }

    @GetMapping("/orders")
    public String list(Model model) {
        model.addAttribute("orders", orderService.forUser(currentUser.get()));
        return "orders/list";
    }

    @GetMapping("/orders/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Order o = orderService.getForUser(id, currentUser.get());
        model.addAttribute("o", o);
        model.addAttribute("review", reviewService.forOrder(o).orElse(null));
        model.addAttribute("adminView", false);
        return "orders/detail";
    }

    @PostMapping("/orders/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes ra) {
        orderService.cancelByCustomer(id, currentUser.get());
        ra.addFlashAttribute("success", "Order cancelled.");
        return "redirect:/orders/" + id;
    }

    @PostMapping("/orders/{id}/review")
    public String review(@PathVariable Long id, @RequestParam int rating,
                         @RequestParam(required = false) String comment, RedirectAttributes ra) {
        reviewService.submit(id, currentUser.get(), rating, comment);
        ra.addFlashAttribute("success", "Thanks for your review!");
        return "redirect:/orders/" + id;
    }

    @PostMapping("/orders/{id}/buy-again")
    public String buyAgain(@PathVariable Long id, RedirectAttributes ra) {
        int added = orderService.reorderToCart(id, currentUser.get());
        if (added == 0) ra.addFlashAttribute("error", "None of these items are available right now.");
        else ra.addFlashAttribute("success", added + " item(s) added to your cart.");
        return "redirect:/cart";
    }
}
