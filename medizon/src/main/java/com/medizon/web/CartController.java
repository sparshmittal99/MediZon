package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.service.CartService;
import com.medizon.service.CurrentUser;
import com.medizon.service.MemberService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

@Controller
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final MemberService memberService;
    private final CurrentUser currentUser;

    /** Signed-out visitors see their session cart and sign in to check out, like any shop. */
    @GetMapping
    public String view(Model model, HttpSession session) {
        Optional<User> signedIn = currentUser.find();
        if (signedIn.isPresent() && signedIn.get().isAdmin()) return "redirect:/admin";
        model.addAttribute("freeAbove", CartService.FREE_DELIVERY_ABOVE);
        if (signedIn.isEmpty()) {
            model.addAttribute("cart", cartService.guestSummary(session));
            model.addAttribute("guest", true);
            return "cart";
        }
        User user = signedIn.get();
        model.addAttribute("cart", cartService.summary(user));
        model.addAttribute("people", memberService.people(user));
        model.addAttribute("common", memberService.common(user));
        model.addAttribute("others", memberService.others(user));
        model.addAttribute("guest", false);
        return "cart";
    }

    @PostMapping("/add")
    public String add(@RequestParam Long medicineId, @RequestParam(defaultValue = "1") int quantity, HttpSession session,
                      @RequestHeader(value = "Referer", required = false) String referer, RedirectAttributes ra) {
        Optional<User> signedIn = currentUser.find();
        if (signedIn.isPresent()) cartService.add(signedIn.get(), medicineId, quantity);
        else cartService.addGuest(session, medicineId, quantity);
        ra.addFlashAttribute("success", "Added to cart.");
        return "redirect:" + (referer != null ? referer : "/cart");
    }

    @PostMapping("/{medicineId}/remove")
    public String remove(@PathVariable Long medicineId, HttpSession session) {
        Optional<User> signedIn = currentUser.find();
        if (signedIn.isPresent()) cartService.setQuantity(signedIn.get(), medicineId, 0);
        else cartService.setGuestQuantity(session, medicineId, 0);
        return "redirect:/cart";
    }
}
