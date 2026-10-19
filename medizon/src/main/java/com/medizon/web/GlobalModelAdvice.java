package com.medizon.web;

import com.medizon.domain.NotificationDevice;
import com.medizon.domain.User;
import com.medizon.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.io.IOException;
import java.util.Optional;

/**
 * Adds the signed-in user, cart count, per-medicine cart quantities and back-in-stock alerts, this device's notification settings and its
 * unread count to every page, plus the seller's contact details and the "Delivers to" location; turns rule
 * violations into flash messages and missing records into 404s.
 */
@ControllerAdvice(basePackages = "com.medizon.web")
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final CurrentUser currentUser;
    private final CartService cartService;
    private final NotificationService notificationService;
    private final DeviceService deviceService;
    private final MemberService memberService;
    private final InvoiceService invoiceService;
    private final DeliveryLocations deliveryLocations;
    private final AddressService addressService;
    private final CatalogService catalog;
    private final StockAlertService stockAlerts;
    private final LiveUpdates liveUpdates;

    @ModelAttribute
    public void common(Model model, HttpServletRequest request, HttpServletResponse response) {
        model.addAttribute("seller", invoiceService.seller());
        model.addAttribute("freeDeliveryAbove", CartService.FREE_DELIVERY_ABOVE);
        Optional<User> signedIn = currentUser.find();
        // Shop by category: the header's category bar and the "All" menu
        // Shop by category: what the stores delivering to the shopper sell
        if (signedIn.map(u -> !u.isStaff()).orElse(true)) {
            model.addAttribute("navCategories", catalog.categories(deliveryLocations.pincode(request)));
        }
        if (signedIn.isEmpty()) {
            HttpSession session = request.getSession(false);
            model.addAttribute("cartCount", cartService.guestCount(session));
            model.addAttribute("cartQuantities", cartService.guestQuantities(session));
            deliveryLocations.current(request, null).ifPresent(l -> model.addAttribute("deliverTo", l));
            return;
        }
        signedIn.ifPresent(u -> {
            if (!u.isStaff()) {
                // Signed in through a remember-me cookie: the login handler didn't run, so move the visitor cart here.
                if (CartService.hasGuestCart(request.getSession(false))) cartService.mergeGuestCart(u, request.getSession(false));
                deliveryLocations.current(request, u).ifPresent(l -> model.addAttribute("deliverTo", l));
                model.addAttribute("savedAddresses", addressService.list(u));
                model.addAttribute("selectedAddressId", deliveryLocations.selectedAddressId(request));
            }
            model.addAttribute("me", u);
            // The change this page is built from; the page reloads itself when a newer one arrives. Only for pages
            // a plain reload can repeat (not a form result shown straight from a POST).
            if ("GET".equals(request.getMethod())) model.addAttribute("liveVersion", liveUpdates.version(u));
            model.addAttribute("cartCount", cartService.count(u));
            NotificationDevice device = null;
            if (!u.isStaff()) {
                model.addAttribute("cartQuantities", cartService.quantities(u));
                model.addAttribute("stockAlertIds", stockAlerts.subscribed(u));
                device = deviceService.current(u, request, response);
                model.addAttribute("device", device);
                // A family account on a new device chooses whose reminders pop up here.
                model.addAttribute("deviceNeedsSetup", !device.isConfigured() && memberService.people(u).size() > 1);
                String query = request.getQueryString();
                model.addAttribute("currentPath", request.getRequestURI().substring(request.getContextPath().length())
                        + (query == null ? "" : "?" + query));
            }
            model.addAttribute("unreadCount", notificationService.unreadCount(u, device));
        });
    }

    /** Someone else's order, plan, file...: a plain 404 page, so the ID isn't confirmed to exist. */
    @ExceptionHandler(NotFoundException.class)
    public void notFound(NotFoundException e, HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public String businessError(BusinessException e, HttpServletRequest request) {
        RequestContextUtils.getOutputFlashMap(request).put("error", e.getMessage());
        String referer = request.getHeader("Referer");
        return "redirect:" + (referer != null ? referer : "/");
    }
}
