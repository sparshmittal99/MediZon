package com.medizon.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gives each request an id (in every log line, the X-Request-Id header and the error page), times it for the slow
 * request log, and reports exceptions that escape it to {@link ErrorMonitor} with the request's details.
 * Runs just inside Spring Security, so it knows who is signed in.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
@Slf4j
@RequiredArgsConstructor
public class RequestMonitorFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    static final String MDC_PATH = "path";
    static final String MDC_METHOD = "method";
    static final String MDC_USER = "user";
    /** Request attribute read by the error page. */
    public static final String REQUEST_ID_ATTRIBUTE = "mz.requestId";

    private final ErrorMonitor errors;
    private final PerformanceMonitor performance;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFL);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, id);
        response.setHeader("X-Request-Id", id);
        MDC.put(MDC_KEY, id);
        MDC.put(MDC_PATH, request.getRequestURI());
        MDC.put(MDC_METHOD, request.getMethod());
        String user = currentUser();
        if (user != null) MDC.put(MDC_USER, user);

        long start = System.nanoTime();
        int status = 0;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } catch (IOException | ServletException | RuntimeException | Error e) {
            if (isClientDisconnect(e)) {
                // The browser went away mid-response (navigated, closed the tab, lost signal): nothing failed here,
                // and there is no one left to send an error page to
                status = 499;
                log.debug("Client disconnected: {} {}", request.getMethod(), request.getRequestURI());
                return;
            }
            status = 500;
            errors.capture(e, request, status);
            throw e;
        } finally {
            performance.recordRequest(request.getMethod(), request.getRequestURI(), status,
                    (System.nanoTime() - start) / 1_000_000, user, id);
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_PATH);
            MDC.remove(MDC_METHOD);
            MDC.remove(MDC_USER);
        }
    }

    /** Tomcat's ClientAbortException, Spring's wrapper for it, or the raw socket error ("Connection reset by peer", "Broken pipe"). */
    static boolean isClientDisconnect(Throwable t) {
        for (int i = 0; t != null && i < 20; i++, t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ClientAbortException || t instanceof AsyncRequestNotUsableException) return true;
            if (t instanceof IOException && t.getMessage() != null) {
                String message = t.getMessage().toLowerCase(Locale.ROOT);
                if (message.contains("connection reset") || message.contains("broken pipe")
                        || message.contains("established connection was aborted")) return true;
            }
        }
        return false;
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || a instanceof AnonymousAuthenticationToken || !a.isAuthenticated() ? null : a.getName();
    }
}
