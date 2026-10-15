package com.medizon.web;

import com.medizon.domain.*;
import com.medizon.dto.CartSummary;
import com.medizon.service.*;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * Checkout starts on the address asked for (just added), else the one picked in the header, else the default,
     * skipping any we don't deliver to.
     */
    @GetMapping("/checkout")
    public String checkout(@RequestParam(required = false) Long address, HttpServletRequest request, Model model) {
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
        CartSummary cart = cartService.summary(user, pin);
        if (cart.isEmpty()) return "redirect:/cart";
        model.addAttribute("cart", cart);
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("prescriptions", prescriptionService.validForUser(user));
        model.addAttribute("addresses", addresses);
        model.addAttribute("serviceable", serviceable);
        model.addAttribute("selectedAddress", selected);
        return "checkout";
    }

    @PostMapping("/checkout")
    public String placeOrder(@RequestParam PaymentMethod paymentMethod,
                             @RequestParam(required = false) Long prescriptionId,
                             @RequestParam(required = false) Long addressId,
                             RedirectAttributes ra) {
        List<Order> placed = orderService.checkout(currentUser.get(), paymentMethod, prescriptionId, addressId);
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
        return "orders/return";
    }

    @PostMapping("/orders/{id}/return")
    public String requestReturn(@PathVariable Long id,
                                @RequestParam(required = false) List<Long> itemId,
                                @RequestParam(required = false) List<Integer> qty,
                                @RequestParam(required = false) ReturnReason reason,
                                @RequestParam(required = false) String details,
                                @RequestParam(required = false) List<MultipartFile> images,
                                RedirectAttributes ra) {
        ReturnRequest r = returnService.request(currentUser.get(), id, itemId, qty, reason, details, images);
        ra.addFlashAttribute("success", "Return " + r.getReturnNumber() + " requested. We'll let you know once it's reviewed.");
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
