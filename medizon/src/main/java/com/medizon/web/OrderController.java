package com.medizon.web;

import com.medizon.domain.*;
import com.medizon.dto.AddressForm;
import com.medizon.dto.CartSummary;
import com.medizon.dto.ReturnPickupForm;
import com.medizon.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Stream;

@Controller
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final CartService cartService;
    private final PrescriptionService prescriptionService;
    private final ReviewService reviewService;
    private final InvoiceService invoiceService;
    private final ReturnService returnService;
    private final DeliveryAreaService deliveryAreas;
    private final AddressService addressService;
    private final DeliveryLocations deliveryLocations;
    private final CurrentUser currentUser;
    private final SupportService supportService;
    private final CouponService couponService;
    private final WalletService walletService;
    private final ReturnSettingsService returnSettings;

    /** The promo code applied at checkout, kept in the session until the order is placed or it's removed. */
    static final String COUPON = "mzCoupon";

    /**
     * Checkout starts on the address asked for (just added), else the one picked in the header, else the default,
     * skipping any we don't deliver to.
     */
    @GetMapping("/checkout")
    public String checkout(@RequestParam(required = false) Long address, HttpServletRequest request, HttpSession session, Model model) {
        User user = currentUser.get();
        List<Address> addresses = addressService.list(user);
        Map<Long, Boolean> serviceable = new HashMap<>();
        addresses.forEach(a -> serviceable.put(a.getId(), deliveryAreas.isServiceable(a.getPincode())));
        Long selected = Stream.of(address, deliveryLocations.selectedAddressId(request))
                .filter(id -> id != null && serviceable.getOrDefault(id, false))
                .findFirst()
                .orElseGet(() -> addresses.stream().filter(a -> serviceable.get(a.getId())).map(Address::getId).findFirst().orElse(null));
        // Priced and split by store for the address it's going to
        String pin = addresses.stream().filter(a -> a.getId().equals(selected)).map(Address::getPincode).findFirst()
                .orElse(deliveryLocations.pincode(request));
        CartSummary cart = cartService.summary(user, pin, (String) session.getAttribute(COUPON));
        if (cart.isEmpty()) return "redirect:/cart";
        model.addAttribute("cart", cart);
        model.addAttribute("paymentMethods", PaymentMethod.payable());
        List<Coupon> promos = couponService.available();
        model.addAttribute("promos", promos);
        model.addAttribute("promosUsedToday", promos.stream().filter(c -> couponService.usedToday(user, c))
                .map(Coupon::getCode).collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("walletBalance", walletService.balance(user));
        model.addAttribute("prescriptions", prescriptionService.validForUser(user));
        // Which prescription(s) each prescription medicine comes from, and anything none of them covers
        model.addAttribute("rxCoverage", prescriptionService.cover(user, OrderService.rxLines(cart)));
        model.addAttribute("addresses", addresses);
        model.addAttribute("serviceable", serviceable);
        model.addAttribute("selectedAddress", selected);
        return "checkout";
    }

    /** Applies a promo typed in or picked from the cards, if it gives a discount on this cart. */
    @PostMapping("/checkout/coupon")
    public String applyCoupon(@RequestParam(required = false) String code, @RequestParam(required = false) Long address,
                              HttpServletRequest request, HttpSession session, RedirectAttributes ra) {
        User user = currentUser.get();
        String pin = addressService.find(address, user).map(Address::getPincode).orElse(deliveryLocations.pincode(request));
        CartSummary cart = cartService.summary(user, pin, code);
        CouponService.Applied applied = cart.getCoupon();
        if (applied == null) {
            ra.addFlashAttribute("error", "Enter a promo code.");
        } else if (!applied.isOk()) {
            ra.addFlashAttribute("error", applied.problem());
        } else {
            session.setAttribute(COUPON, applied.coupon().getCode());
            ra.addFlashAttribute("success", applied.coupon().getCode() + " applied. You save ₹" + applied.discount() + ".");
        }
        return "redirect:/checkout" + (address == null ? "" : "?address=" + address);
    }

    @PostMapping("/checkout/coupon/remove")
    public String removeCoupon(@RequestParam(required = false) Long address, HttpSession session) {
        session.removeAttribute(COUPON);
        return "redirect:/checkout" + (address == null ? "" : "?address=" + address);
    }

    @PostMapping("/checkout")
    public String placeOrder(@RequestParam(required = false) PaymentMethod paymentMethod,
                             @RequestParam(required = false) Long addressId,
                             @RequestParam(defaultValue = "false") boolean useWallet,
                             HttpSession session, RedirectAttributes ra) {
        List<Order> placed = orderService.checkout(currentUser.get(), paymentMethod, addressId, (String) session.getAttribute(COUPON), useWallet);
        session.removeAttribute(COUPON);
        if (placed.size() == 1) {
            ra.addFlashAttribute("success", "Order " + placed.get(0).getOrderNumber() + " placed. We'll notify you as it moves.");
            return "redirect:/orders/" + placed.get(0).getId();
        }
        ra.addFlashAttribute("success", "Your cart comes from " + placed.size() + " stores, so it's " + placed.size() + " orders: "
                + placed.stream().map(Order::getOrderNumber).collect(java.util.stream.Collectors.joining(", "))
                + ". Each has its own delivery and invoice.");
        return "redirect:/orders";
    }

    /** Order sections on the customer's Orders page. */
    private static final Map<String, Set<OrderStatus>> TABS = Map.of(
            "pending", EnumSet.of(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderStatus.SHIPPED),
            "completed", EnumSet.of(OrderStatus.DELIVERED),
            "cancelled", EnumSet.of(OrderStatus.CANCELLED));

    /**
     * Orders in four sections: pending (placed, confirmed, shipped), completed (delivered), cancelled, and returns.
     * Without a tab it opens on pending, or on completed when nothing is on its way.
     */
    @GetMapping("/orders")
    public String list(@RequestParam(required = false) String tab, Model model) {
        User user = currentUser.get();
        List<Order> all = orderService.forUser(user);
        List<ReturnRequest> returns = returnService.forUser(user);

        Map<String, List<Order>> byTab = new LinkedHashMap<>();
        TABS.forEach((name, statuses) -> byTab.put(name, all.stream().filter(o -> statuses.contains(o.getStatus())).toList()));
        String selected = tab != null && (TABS.containsKey(tab) || tab.equals("returns")) ? tab
                : !byTab.get("pending").isEmpty() || all.isEmpty() ? "pending" : "completed";

        // Latest return per order, for the badge on completed orders
        Map<Long, ReturnRequest> latestReturn = new HashMap<>();
        returns.forEach(r -> latestReturn.putIfAbsent(r.getOrder().getId(), r));   // newest first

        model.addAttribute("tab", selected);
        model.addAttribute("orders", selected.equals("returns") ? List.of() : byTab.get(selected));
        model.addAttribute("counts", Map.of("pending", byTab.get("pending").size(), "completed", byTab.get("completed").size(),
                "cancelled", byTab.get("cancelled").size(), "returns", returns.size()));
        model.addAttribute("returns", returns);
        model.addAttribute("latestReturn", latestReturn);
        model.addAttribute("hasOrders", !all.isEmpty());
        return "orders/list";
    }

    @GetMapping("/orders/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Order o = orderService.getForUser(id, currentUser.get());
        model.addAttribute("o", o);
        model.addAttribute("tickets", supportService.forOrder(currentUser.get(), o));
        model.addAttribute("placedWith", orderService.placedWith(o));
        model.addAttribute("review", reviewService.forOrder(o).orElse(null));
        model.addAttribute("returns", returnService.forOrder(o));
        model.addAttribute("returnBlocker", returnService.blocker(o).orElse(null));
        model.addAttribute("hasReturnable", returnService.hasReturnableItems(o));
        model.addAttribute("returnBy", returnService.returnBy(o));
        model.addAttribute("adminView", false);
        return "orders/detail";
    }

    @GetMapping("/orders/{id}/invoice")
    public String invoice(@PathVariable Long id, Model model) {
        Order o = orderService.getForUser(id, currentUser.get());
        model.addAttribute("inv", invoiceService.forOrder(o));
        model.addAttribute("adminView", false);
        return "orders/invoice";
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
    public String buyAgain(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        int added = orderService.reorderToCart(id, currentUser.get(), deliveryLocations.pincode(request));
        if (added == 0) ra.addFlashAttribute("error", "None of these items are available right now.");
        else ra.addFlashAttribute("success", added + " item(s) added to your cart.");
        return "redirect:/cart";
    }

    // ---------------------------------------------------------------- returns

    @GetMapping("/orders/{id}/return")
    public String returnForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Order o = orderService.getForUser(id, currentUser.get());
        String blocker = returnService.blocker(o).orElse(null);
        if (blocker != null) {
            ra.addFlashAttribute("error", blocker);
            return "redirect:/orders/" + id;
        }
        model.addAttribute("o", o);
        model.addAttribute("returnable", returnService.returnable(o));
        model.addAttribute("returnBy", returnService.returnBy(o));
        model.addAttribute("reasons", ReturnReason.values());
        model.addAttribute("maxImages", ReturnService.MAX_IMAGES);
        // Step 2: pickup from where it was delivered (or another address we serve), and where cash orders are refunded
        List<Address> addresses = addressService.list(currentUser.get());
        Map<Long, Boolean> serviceable = new HashMap<>();
        addresses.forEach(a -> serviceable.put(a.getId(), deliveryAreas.isServiceable(a.getPincode())));
        boolean orderAddressOk = returnService.canPickUpFromOrderAddress(o);
        ReturnPickupForm pickup = new ReturnPickupForm();
        pickup.setNewAddress(AddressForm.blank(currentUser.get()));
        pickup.setPickupDate(returnService.pickupDate());
        if (!orderAddressOk) {
            pickup.setPickupFrom(addresses.stream().filter(a -> serviceable.get(a.getId())).map(a -> String.valueOf(a.getId()))
                    .findFirst().orElse(ReturnPickupForm.NEW_ADDRESS));
        }
        model.addAttribute("pickup", pickup);
        model.addAttribute("addresses", addresses);
        model.addAttribute("serviceable", serviceable);
        model.addAttribute("orderAddressOk", orderAddressOk);
        model.addAttribute("addressTypes", AddressType.values());
        Map<String, String> timings = new HashMap<>();
        ReturnSettings rs = returnSettings.get();
        for (RefundMode m : RefundMode.values()) timings.put(m.name(), m.timing(o.getPaymentMethod(), rs));
        model.addAttribute("refundTimings", timings);
        return "orders/return";
    }

    @PostMapping("/orders/{id}/return")
    public String requestReturn(@PathVariable Long id,
                                @RequestParam(required = false) List<Long> itemId,
                                @RequestParam(required = false) List<Integer> qty,
                                @RequestParam(required = false) ReturnReason reason,
                                @RequestParam(required = false) String details,
                                @RequestParam(required = false) List<MultipartFile> images,
                                @ModelAttribute ReturnPickupForm pickup,
                                RedirectAttributes ra) {
        ReturnRequest r = returnService.request(currentUser.get(), id, itemId, qty, reason, details, images, pickup);
        ra.addFlashAttribute("success", "Return " + r.getReturnNumber() + " requested. Pickup is planned for "
                + r.getPickupDate().format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH))
                + "; we'll let you know once it's reviewed.");
        return "redirect:/orders/" + id;
    }

    /** Return photos: streamed to the customer who sent them and to admins only. */
    @GetMapping("/returns/{id}/images/{index}")
    public ResponseEntity<Resource> returnImage(@PathVariable Long id, @PathVariable int index) {
        ReturnImage img = returnService.image(id, index, currentUser.get());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.getContentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"return-" + id + "-" + (index + 1) + "\"")
                .body(returnService.file(img));
    }
}
