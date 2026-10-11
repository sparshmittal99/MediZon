package com.medizon.web;

import com.medizon.service.CartService;
import com.medizon.service.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final CurrentUser currentUser;

    @GetMapping
    public String view(Model model) {
        model.addAttribute("cart", cartService.summary(currentUser.get()));
        model.addAttribute("freeAbove", CartService.FREE_DELIVERY_ABOVE);
        return "cart";
    }

    @PostMapping("/add")
    public String add(@RequestParam Long medicineId, @RequestParam(defaultValue = "1") int quantity,
                      @RequestHeader(value = "Referer", required = false) String referer, RedirectAttributes ra) {
        cartService.add(currentUser.get(), medicineId, quantity);
        ra.addFlashAttribute("success", "Added to cart.");
        return "redirect:" + (referer != null ? referer : "/cart");
    }

    @PostMapping("/{id}/remove")
    public String remove(@PathVariable Long id) {
        cartService.remove(currentUser.get(), id);
        return "redirect:/cart";
    }
}
