package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.service.CartService;
import com.medizon.service.CurrentUser;
import com.medizon.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final MemberService memberService;
    private final CurrentUser currentUser;

    @GetMapping
    public String view(Model model) {
        User user = currentUser.get();
        model.addAttribute("cart", cartService.summary(user));
        model.addAttribute("people", memberService.people(user));
        model.addAttribute("common", memberService.common(user));
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

    /** Who a line is for; the cart page saves this as boxes are ticked, this is the no-JavaScript path. */
    @PostMapping("/{medicineId}/members")
    public String members(@PathVariable Long medicineId, @RequestParam(required = false) List<Long> memberIds) {
        cartService.setMembers(currentUser.get(), medicineId, memberIds);
        return "redirect:/cart";
    }
}
