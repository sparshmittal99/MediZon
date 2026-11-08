package com.medizon.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Customer URLs carry masked ids ({@link MaskedUrls}). This turns the tokens back into ids before anything else sees
 * the request (security, controllers), so controllers keep their plain {@code Long} ids. A page opened with a plain
 * id (an old email, a saved notification, the app) is redirected to its masked address, so customers only ever see
 * tokens in the address bar.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MaskedUrlFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ctx = request.getContextPath();
        String uri = request.getRequestURI();
        String path = uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
        if (!MaskedUrls.customerPath(path)) {
            chain.doFilter(request, response);
            return;
        }
        String query = request.getQueryString();

        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
            String original = path + (query == null ? "" : "?" + query);
            String masked = MaskedUrls.mask(original, "");
            if (!masked.equals(original)) {
                response.setStatus(HttpServletResponse.SC_FOUND);   // not 301: browsers would cache it for good
                response.setHeader("Location", ctx + masked);
                return;
            }
        }

        String plainPath = MaskedUrls.unmaskPath(path);
        // Only the query string carries masked ids (forms post plain ids), so form bodies and uploads aren't read here
        String plainQuery = query == null ? null : MaskedUrls.unmaskQuery(query);
        boolean paramsMasked = query != null && !query.equals(plainQuery);
        if (plainPath.equals(path) && !paramsMasked) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new Unmasked(request, ctx + plainPath, plainPath, plainQuery), response);
    }

    /** The same request with ids where the address had tokens. */
    static final class Unmasked extends HttpServletRequestWrapper {
        private final String uri;
        private final String servletPath;
        private final String query;
        private Map<String, String[]> params;

        Unmasked(HttpServletRequest request, String uri, String servletPath, String query) {
            super(request);
            this.uri = uri;
            this.servletPath = servletPath;
            this.query = query;
        }

        @Override public String getRequestURI() { return uri; }
        @Override public String getServletPath() { return servletPath; }
        @Override public String getPathInfo() { return null; }
        @Override public String getQueryString() { return query; }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            url.append(getScheme()).append("://").append(getServerName());
            int port = getServerPort();
            if (!(("http".equals(getScheme()) && port == 80) || ("https".equals(getScheme()) && port == 443))) url.append(':').append(port);
            return url.append(uri);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            if (params == null) {
                Map<String, String[]> out = new LinkedHashMap<>();
                super.getParameterMap().forEach((name, values) -> out.put(name,
                        Arrays.stream(values).map(v -> MaskedUrls.unmaskParam(name, v)).toArray(String[]::new)));
                params = Collections.unmodifiableMap(out);
            }
            return params;
        }

        @Override public String getParameter(String name) { String[] v = getParameterMap().get(name); return v == null || v.length == 0 ? null : v[0]; }
        @Override public String[] getParameterValues(String name) { String[] v = getParameterMap().get(name); return v == null ? null : v.clone(); }
        @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(getParameterMap().keySet()); }
    }
}
