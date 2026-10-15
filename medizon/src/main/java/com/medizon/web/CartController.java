package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.service.CartService;
import com.medizon.service.CurrentUser;
import com.medizon.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final DeliveryLocations deliveryLocations;

    /** Signed-out visitors see their session cart and sign in to check out, like any shop. */
    @GetMapping
    public String view(Model model, HttpSession session, HttpServletRequest request) {
        Optional<User> signedIn = currentUser.find();
        if (signedIn.isPresent() && signedIn.get().isStaff()) return "redirect:/admin";
        String pin = deliveryLocations.pincode(request);
        model.addAttribute("freeAbove", CartService.FREE_DELIVERY_ABOVE);
        if (signedIn.isEmpty()) {
            model.addAttribute("cart", cartService.guestSummary(session, pin));
            model.addAttribute("guest", true);
            return "cart";
        }
        User user = signedIn.get();
        model.addAttribute("cart", cartService.summary(user, pin));
        model.addAttribute("people", memberService.people(user));
        model.addAttribute("common", memberService.common(user));
        model.addAttribute("others", memberService.others(user));
        model.addAttribute("guest", false);
        return "cart";
    }

    @PostMapping("/add")
    public String add(@RequestParam Long medicineId, @RequestParam(defaultValue = "1") int quantity, HttpSession session,
                      @RequestHeader(value = "Referer", required = false) String referer, HttpServletRequest request,
                      RedirectAttributes ra) {
        Optional<User> signedIn = currentUser.find();
        String pin = deliveryLocations.pincode(request);
        if (signedIn.isPresent()) cartService.add(signedIn.get(), medicineId, quantity, pin);
        else cartService.addGuest(session, medicineId, quantity, pin);
        ra.addFlashAttribute("success", "Added to cart.");
        return "redirect:" + (referer != null ? referer : "/cart");
    }

    @PostMapping("/{medicineId}/remove")
    public String remove(@PathVariable Long medicineId, HttpSession session) {
        Optional<User> signedIn = currentUser.find();
        if (signedIn.isPresent()) cartService.setQuantity(signedIn.get(), medicineId, 0, null);
        else cartService.setGuestQuantity(session, medicineId, 0, null);
        return "redirect:/cart";
    }
}
