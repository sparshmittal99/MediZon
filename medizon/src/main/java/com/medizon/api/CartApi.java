package com.medizon.api;

import com.medizon.domain.CartItem;
import com.medizon.domain.User;
import com.medizon.dto.CartSummary;
import com.medizon.service.BusinessException;
import com.medizon.service.CartService;
import com.medizon.service.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

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
    public Map<String, Object> cart() {
        User user = currentUser.get();
        return Map.of("quantities", cartService.quantities(user), "cartCount", cartService.count(user));
    }

    /** Sets the quantity and returns the new totals, already formatted the way the cart page shows them. */
    @PostMapping("/{medicineId}")
    public Map<String, Object> setQuantity(@PathVariable Long medicineId, @RequestBody QuantityRequest req) {
        User user = currentUser.get();
        int qty = cartService.setQuantity(user, medicineId, req.quantity());
        CartSummary c = cartService.summary(user);
        BigDecimal line = c.getItems().stream().filter(i -> i.getMedicine().getId().equals(medicineId))
                .map(CartItem::getLineTotal).findFirst().orElse(BigDecimal.ZERO);

        Map<String, Object> out = new HashMap<>();
        out.put("medicineId", medicineId);
        out.put("quantity", qty);
        out.put("cartCount", cartService.count(user));
        out.put("lineTotal", money(line));
        out.put("subtotal", money(c.getSubtotal()));
        out.put("delivery", c.getDeliveryFee().signum() == 0 ? "Free" : "₹" + c.getDeliveryFee().toPlainString());
        out.put("freeDelivery", c.getDeliveryFee().signum() == 0);
        out.put("total", money(c.getTotal()));
        out.put("prescriptionRequired", c.isPrescriptionRequired());
        out.put("empty", c.isEmpty());
        return out;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> rejected(BusinessException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static String money(BigDecimal v) {
        return "₹" + new DecimalFormat("#,##0.00").format(v);
    }
}
