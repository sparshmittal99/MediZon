package com.medizon.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Answers {@code HEAD /} at once. Uptime monitors and the host's health check send it only to see the site is up,
 * but Spring would build the whole home page for it (the catalogue query and the template) and throw the body away:
 * right after a restart those pings queued up for seconds each. A HEAD has no body, so nothing is lost. Health
 * checks that want the database checked too should use {@code /actuator/health}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("HEAD".equals(request.getMethod()) && "/".equals(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/html;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        chain.doFilter(request, response);
    }
}
