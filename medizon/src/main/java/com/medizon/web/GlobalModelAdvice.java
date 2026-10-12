package com.medizon.web;

import com.medizon.domain.NotificationDevice;
import com.medizon.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * Adds the signed-in user, cart count, per-medicine cart quantities, this device's notification settings and its
 * unread count to every page; turns rule violations into flash messages.
 */
@ControllerAdvice(basePackages = "com.medizon.web")
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final CurrentUser currentUser;
    private final CartService cartService;
    private final NotificationService notificationService;
    private final DeviceService deviceService;
    private final MemberService memberService;

    @ModelAttribute
    public void common(Model model, HttpServletRequest request, HttpServletResponse response) {
        currentUser.find().ifPresent(u -> {
            model.addAttribute("me", u);
            model.addAttribute("cartCount", cartService.count(u));
            NotificationDevice device = null;
            if (!u.isAdmin()) {
                model.addAttribute("cartQuantities", cartService.quantities(u));
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

    @ExceptionHandler(BusinessException.class)
    public String businessError(BusinessException e, HttpServletRequest request) {
        RequestContextUtils.getOutputFlashMap(request).put("error", e.getMessage());
        String referer = request.getHeader("Referer");
        return "redirect:" + (referer != null ? referer : "/");
    }
}
