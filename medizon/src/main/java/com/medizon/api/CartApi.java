package com.medizon.api;

import com.medizon.domain.CartItem;
import com.medizon.domain.User;
import com.medizon.dto.CartSummary;
import com.medizon.service.BusinessException;
import com.medizon.service.CartService;
import com.medizon.service.CurrentUser;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Used by the quantity steppers on product cards, product pages and the cart to change the cart without reloading. */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartApi {

    private final CartService cartService;
    private final CurrentUser currentUser;

    public record QuantityRequest(int quantity) {}

    /** Packs per medicine id and the badge count, so open pages can resync after the assistant changes the cart. */
    @GetMapping
    public Map<String, Object> cart(HttpSession session) {
        Optional<User> user = currentUser.find();
        if (user.isEmpty()) {
            return Map.of("quantities", cartService.guestQuantities(session), "cartCount", cartService.guestCount(session));
        }
        return Map.of("quantities", cartService.quantities(user.get()), "cartCount", cartService.count(user.get()));
    }

    /**
     * Sets the quantity and returns the new totals, already formatted the way the cart page shows them. Signed-out
     * visitors change their session cart.
     */
    @PostMapping("/{medicineId}")
    public Map<String, Object> setQuantity(@PathVariable Long medicineId, @RequestBody QuantityRequest req, HttpSession session) {
        Optional<User> user = currentUser.find();
        int qty;
        CartSummary c;
        long count;
        if (user.isPresent()) {
            qty = cartService.setQuantity(user.get(), medicineId, req.quantity());
            c = cartService.summary(user.get());
            count = cartService.count(user.get());
        } else {
            qty = cartService.setGuestQuantity(session, medicineId, req.quantity());
            c = cartService.guestSummary(session);
            count = cartService.guestCount(session);
        }
        BigDecimal line = c.getItems().stream().filter(i -> i.getMedicine().getId().equals(medicineId))
                .map(CartItem::getLineTotal).findFirst().orElse(BigDecimal.ZERO);

        Map<String, Object> out = new HashMap<>();
        out.put("medicineId", medicineId);
        out.put("quantity", qty);
        out.put("cartCount", count);
        out.put("lineTotal", money(line));
        out.put("subtotal", money(c.getSubtotal()));
        out.put("delivery", c.getDeliveryFee().signum() == 0 ? "Free" : "₹" + c.getDeliveryFee().toPlainString());
        out.put("freeDelivery", c.getDeliveryFee().signum() == 0);
        out.put("roundOff", c.getRoundOffLabel());
        out.put("rounded", c.getRoundOff().signum() != 0);
        out.put("total", money(c.getTotal()));
        out.put("prescriptionRequired", c.isPrescriptionRequired());
        out.put("empty", c.isEmpty());
        c.getItems().stream().filter(i -> i.getMedicine().getId().equals(medicineId)).findFirst()
                .ifPresent(i -> out.put("allocation", i.getAllocationById()));
        return out;
    }

    /** Packs per member id; members missing or at 0 aren't on the line. */
    public record AllocationRequest(Map<Long, Integer> packs) {}

    /** Sets how many packs each person gets; the line's quantity becomes the total. Returns the new totals. */
    @PostMapping("/{medicineId}/allocation")
    public Map<String, Object> setAllocation(@PathVariable Long medicineId, @RequestBody AllocationRequest req, HttpSession session) {
        CartItem item = cartService.setAllocation(currentUser.get(), medicineId, req.packs());
        return setQuantity(medicineId, new QuantityRequest(item.getQuantity()), session);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> rejected(BusinessException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static String money(BigDecimal v) {
        return "₹" + new DecimalFormat("#,##0.00").format(v);
    }
}
