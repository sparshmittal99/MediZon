package com.medizon.api;

import com.medizon.domain.Cart;
import com.medizon.domain.CartItem;
import com.medizon.domain.User;
import com.medizon.dto.CartSummary;
import com.medizon.service.CartService;
import com.medizon.service.CurrentUser;
import com.medizon.service.OrderSettingsService;
import com.medizon.web.DeliveryLocations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Used by the quantity steppers on product cards, product pages and the cart to change the cart without reloading.
 * Errors come back as JSON from {@link ApiErrors}.
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartApi {

    private final CartService cartService;
    private final CurrentUser currentUser;
    private final DeliveryLocations deliveryLocations;
    private final OrderSettingsService orderSettings;

    public record QuantityRequest(int quantity) {}

    public record CartLine(Long cartItemId, Long medicineId, String name, int quantity, BigDecimal unitPrice,
                           BigDecimal lineTotal, boolean available, String store, Map<Long, Integer> allocation) {
        static CartLine of(CartItem i) {
            return new CartLine(i.getId(), i.getMedicine().getId(), i.getMedicine().getName(), i.getQuantity(), i.getUnitPrice(),
                    i.getLineTotal(), i.isAvailable(), i.isAvailable() ? i.getOffer().store().getName() : null,
                    i.getAllocationById());
        }
    }

    /**
     * The whole cart, priced for the "Delivers to" location. Packs per medicine id and the badge count are also there
     * so open pages can resync after the assistant changes the cart. {@code cartId} is the account's open cart: the
     * same across sign-ins and devices, new after each checkout, and null until the first medicine goes in. A
     * signed-out visitor's cart lives in the session and has none.
     */
    @GetMapping
    public Map<String, Object> cart(HttpSession session, HttpServletRequest request) {
        Optional<User> user = currentUser.find();
        String pin = deliveryLocations.pincode(request);
        CartSummary c = user.isPresent() ? cartService.summary(user.get(), pin) : cartService.guestSummary(session, pin);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cartId", user.flatMap(cartService::activeCart).map(Cart::getId).orElse(null));
        out.put("guest", user.isEmpty());
        out.put("items", c.getItems().stream().map(CartLine::of).toList());
        out.put("quantities", user.isPresent() ? cartService.quantities(user.get()) : cartService.guestQuantities(session));
        out.put("cartCount", user.isPresent() ? cartService.count(user.get()) : cartService.guestCount(session));
        out.put("subtotal", c.getSubtotal());
        out.put("deliveryFee", c.getDeliveryFee());
        out.put("roundOff", c.getRoundOff());
        out.put("total", c.getTotal());
        out.put("prescriptionRequired", c.isPrescriptionRequired());
        out.put("shipmentCount", c.getByStore().size());
        minimum(out, c);
        return out;
    }

    /**
     * Sets the quantity and returns the line and the new totals. Amounts are plain numbers in rupees, as in
     * {@link #cart}; the page formats them. Signed-out visitors change their session cart.
     */
    @PostMapping("/{medicineId}")
    public Map<String, Object> setQuantity(@PathVariable Long medicineId, @RequestBody QuantityRequest req, HttpSession session,
                                           HttpServletRequest request) {
        Optional<User> user = currentUser.find();
        String pin = deliveryLocations.pincode(request);
        int qty;
        CartSummary c;
        long count;
        if (user.isPresent()) {
            qty = cartService.setQuantity(user.get(), medicineId, req.quantity(), pin);
            c = cartService.summary(user.get(), pin);
            count = cartService.count(user.get());
        } else {
            qty = cartService.setGuestQuantity(session, medicineId, req.quantity(), pin);
            c = cartService.guestSummary(session, pin);
            count = cartService.guestCount(session);
        }
        BigDecimal line = c.getItems().stream().filter(i -> i.getMedicine().getId().equals(medicineId))
                .map(CartItem::getLineTotal).findFirst().orElse(BigDecimal.ZERO);

        Map<String, Object> out = new HashMap<>();
        out.put("cartId", user.flatMap(cartService::activeCart).map(Cart::getId).orElse(null));
        out.put("medicineId", medicineId);
        out.put("quantity", qty);
        out.put("cartCount", count);
        out.put("lineTotal", line);
        out.put("subtotal", c.getSubtotal());
        out.put("deliveryFee", c.getDeliveryFee());
        out.put("roundOff", c.getRoundOff());
        out.put("total", c.getTotal());
        out.put("prescriptionRequired", c.isPrescriptionRequired());
        out.put("empty", c.isEmpty());
        out.put("shipmentCount", c.getByStore().size());
        minimum(out, c);
        c.getItems().stream().filter(i -> i.getMedicine().getId().equals(medicineId)).findFirst().ifPresent(i -> {
            out.put("cartItemId", i.getId());   // null for a signed-out visitor's session cart
            out.put("allocation", i.getAllocationById());
            // A bigger quantity can move the line to another store, with its own price and delivery time
            out.put("unitPrice", i.getUnitPrice());
            if (i.isAvailable()) out.put("store", i.getOffer().store().getName());
        });
        return out;
    }

    /** The minimum order value, and how much more the medicines need to reach it (zero once they do). */
    private void minimum(Map<String, Object> out, CartSummary c) {
        BigDecimal min = orderSettings.minOrderValue();
        out.put("minOrderValue", min);
        out.put("minOrderShort", OrderSettingsService.shortBy(c.getSubtotal(), min));
    }

    /** Packs per member id; members missing or at 0 aren't on the line. */
    public record AllocationRequest(Map<Long, Integer> packs) {}

    /** Sets how many packs each person gets; the line's quantity becomes the total. Returns the new totals. */
    @PostMapping("/{medicineId}/allocation")
    public Map<String, Object> setAllocation(@PathVariable Long medicineId, @RequestBody AllocationRequest req, HttpSession session,
                                             HttpServletRequest request) {
        CartItem item = cartService.setAllocation(currentUser.get(), medicineId, req.packs(), deliveryLocations.pincode(request));
        return setQuantity(medicineId, new QuantityRequest(item.getQuantity()), session, request);
    }
}
