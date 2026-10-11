package com.medizon.web;

import com.medizon.service.BusinessException;
import com.medizon.service.CartService;
import com.medizon.service.CurrentUser;
import com.medizon.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * Adds the signed-in user, cart count, per-medicine cart quantities and unread count to every page;
 * turns rule violations into flash messages.
 */
@ControllerAdvice(basePackages = "com.medizon.web")
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final CurrentUser currentUser;
    private final CartService cartService;
    private final NotificationService notificationService;

    @ModelAttribute
    public void common(Model model) {
        currentUser.find().ifPresent(u -> {
            model.addAttribute("me", u);
            model.addAttribute("cartCount", cartService.count(u));
            if (!u.isAdmin()) model.addAttribute("cartQuantities", cartService.quantities(u));
            model.addAttribute("unreadCount", notificationService.unreadCount(u));
        });
    }

    @ExceptionHandler(BusinessException.class)
    public String businessError(BusinessException e, HttpServletRequest request) {
        RequestContextUtils.getOutputFlashMap(request).put("error", e.getMessage());
        String referer = request.getHeader("Referer");
        return "redirect:" + (referer != null ? referer : "/");
    }
}
