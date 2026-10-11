package com.medizon.web;

import com.medizon.repository.DailyVisitRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDate;

/**
 * Counts site traffic for the admin dashboard: every page a shopper loads is a page view,
 * and the first page a browser session loads each day is a visit. Admin pages and APIs aren't counted.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VisitInterceptor implements HandlerInterceptor {

    private static final String SESSION_KEY = "mz.visitDay";

    private final DailyVisitRepository visits;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"GET".equals(request.getMethod()) || !(handler instanceof HandlerMethod)) return true;

        LocalDate today = LocalDate.now();
        HttpSession session = request.getSession();
        boolean newVisit = !today.equals(session.getAttribute(SESSION_KEY));
        if (newVisit) session.setAttribute(SESSION_KEY, today);
        try {
            visits.record(today, newVisit ? 1 : 0);
        } catch (RuntimeException e) {
            log.warn("Couldn't record visit: {}", e.getMessage());   // never break a page over analytics
        }
        return true;
    }
}
