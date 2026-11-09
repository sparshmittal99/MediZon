package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * A signed-in customer without a mobile number (a new Sign in with Google account, or an older account) is sent to
 * "Add your mobile number" from any page until they add one. Checked once per session; staff are never asked.
 */
@Component
@RequiredArgsConstructor
public class PhoneRequiredInterceptor implements HandlerInterceptor {

    /** Session: the id of the account already known to have a number. */
    static final String OK = "mz.phoneOk";

    private final CurrentUser currentUser;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!request.isUserInRole("CUSTOMER")) return true;
        HttpSession session = request.getSession(false);
        Optional<User> user = currentUser.find();
        if (user.isEmpty()) return true;
        Long id = user.get().getId();
        if (session != null && Objects.equals(session.getAttribute(OK), id)) return true;
        if (user.get().getPhone() != null && !user.get().getPhone().isBlank()) {
            request.getSession().setAttribute(OK, id);
            return true;
        }
        String next = "GET".equals(request.getMethod()) ? request.getRequestURI().substring(request.getContextPath().length())
                + (request.getQueryString() != null ? "?" + request.getQueryString() : "") : "/";
        response.sendRedirect(request.getContextPath() + "/account/phone?next=" + UriUtils.encode(next, StandardCharsets.UTF_8));
        return false;
    }

    /** Where to go once the number is saved: a page on this site only. */
    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")
                || next.startsWith("/account/phone")) return "/";
        return next;
    }
}
