package com.medizon.config;

import com.medizon.service.CartService;
import com.medizon.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Where pages may load code and data from: this site, jsDelivr (Bootstrap, Bootstrap Icons, Leaflet), Google
     * Translate (the language picker), OpenStreetMap (address search; map tiles are images) and Google Fonts. Images
     * may come from any https site, since a product's picture can be a link. No inline scripts or event handlers;
     * inline styles are allowed (Bootstrap and the translator use them). What the browser blocks is reported to
     * Admin > Monitoring as a browser error.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://cdn.jsdelivr.net https://translate.google.com https://translate.googleapis.com"
                    + " https://translate-pa.googleapis.com https://www.gstatic.com",
            "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com https://www.gstatic.com"
                    + " https://translate.googleapis.com",
            "font-src 'self' data: https://cdn.jsdelivr.net https://fonts.gstatic.com",
            "img-src 'self' data: blob: https:",
            "connect-src 'self' https://nominatim.openstreetmap.org https://translate.googleapis.com"
                    + " https://translate-pa.googleapis.com",
            "frame-src https://translate.google.com https://translate.googleapis.com",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "report-uri /api/monitoring/csp-report");

    /**
     * Google's website translator injects inline scripts, so a visitor who picked another language gets the same
     * policy with inline scripts allowed. Everyone browsing in English keeps the strict one.
     */
    static final String CONTENT_SECURITY_POLICY_TRANSLATED =
            CONTENT_SECURITY_POLICY.replace("script-src 'self'", "script-src 'self' 'unsafe-inline'");

    /** The footer's language picker stores the choice in Google's "googtrans" cookie (see medizon.js). */
    private static boolean usesTranslator(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie c : cookies) {
            if ("googtrans".equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) return true;
        }
        return false;
    }

    private static final RequestMatcher API = new AntPathRequestMatcher("/api/**");

    /**
     * Every sign-in gets its own HTTP session, so any number of customers and admins can be signed in
     * at once (one per browser or private window). The registry lets admins see who is online and
     * sign a user out everywhere.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AppUserDetailsService userDetailsService,
                                           SessionRegistry sessionRegistry, @Lazy UserService userService,
                                           @Lazy CartService cartService, LoginThrottle loginThrottle,
                                           @Value("${medizon.monitoring.token:}") String monitoringToken,
                                           @Value("${medizon.security.remember-me-key:}") String rememberMeKey) throws Exception {
        http
            // Refuses sign-in while an email or address is locked out for too many wrong passwords
            .addFilterBefore(loginThrottleFilter(loginThrottle), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Monitoring: health for load balancers and uptime checks; Prometheus with a bearer token or as an admin
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/prometheus").access(adminOrToken(monitoringToken))
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // Browsers report JavaScript errors and page load times, signed in or not
                .requestMatchers("/api/monitoring/**").permitAll()
                .requestMatchers("/", "/medicines", "/medicines/**", "/help", "/register", "/login",
                        "/css/**", "/js/**", "/images/**", "/medicine-images/**", "/error").permitAll()
                // Site icons, also under their content-hashed names (favicon-3f9a….svg) from the resource chain
                .requestMatchers("/favicon*", "/apple-touch-icon*").permitAll()
                // Visitors can fill a cart and pick a delivery location before signing in; checkout needs an account.
                // Admin accounts are turned away in CartController / CartService.
                .requestMatchers("/cart", "/cart/add", "/cart/*/remove", "/api/cart", "/api/cart/*", "/api/location/**").permitAll()
                // The back office. One admin can do everything; every other staff role only reaches its own pages.
                .requestMatchers("/admin/users/**", "/admin/stores/**", "/admin/locations/**", "/admin/monitoring/**").hasRole("ADMIN")
                .requestMatchers("/admin/prescriptions/**").hasAnyRole("ADMIN", "PHARMACIST")
                .requestMatchers("/admin/reviews/**").hasAnyRole("ADMIN", "SUPPORT")
                .requestMatchers("/admin/sales", "/admin/sales.csv", "/admin/sales.xlsx").hasAnyRole("ADMIN", "FINANCE")
                .requestMatchers("/admin/returns/settings").hasRole("ADMIN")
                .requestMatchers("/admin/medicines/**", "/admin/returns/**").hasAnyRole("ADMIN", "STORE")
                // Support and finance can look orders up and open invoices; only the store (and admin) move them along
                .requestMatchers(HttpMethod.POST, "/admin/orders/**").hasAnyRole("ADMIN", "STORE")
                .requestMatchers("/admin/orders/**").hasAnyRole("ADMIN", "STORE", "SUPPORT", "FINANCE")
                .requestMatchers("/admin").hasRole("STAFF")
                // Every staff role has a support desk: support and the admin see all tickets, each team its own (SupportService)
                .requestMatchers("/admin/support", "/admin/support/**").hasRole("STAFF")
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/prescriptions/*/file", "/returns/*/images/*").authenticated()   // owner or admin, checked in the service
                .requestMatchers("/support/attachments/**").authenticated()   // the customer or the staff on the ticket (SupportService)
                // Shopping and dose reminders are for customers; admin accounts only run the store.
                // The assistant API is open to both roles; each role gets its own actions in AssistantTools.
                .requestMatchers("/cart/**", "/api/cart/**", "/api/orders/**", "/checkout", "/checkout/**", "/wallet", "/orders/**", "/plans/**",
                        "/prescriptions/**", "/support", "/support/**", "/members/**", "/addresses/**", "/billing-address/**", "/notifications/settings", "/notifications/devices/**",
                        "/stock-alerts/**", "/api/stock-alerts/**").hasRole("CUSTOMER")
                .anyRequest().authenticated())
            // sendBeacon can't send the CSRF header; these endpoints only record telemetry
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/monitoring/**"))
            // On top of Spring's defaults (nosniff, X-Frame-Options DENY, HSTS over HTTPS, no-store)
            .headers(headers -> headers
                .addHeaderWriter((request, response) -> response.setHeader("Content-Security-Policy",
                        usesTranslator(request) ? CONTENT_SECURITY_POLICY_TRANSLATED : CONTENT_SECURITY_POLICY))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                        "geolocation=(self), camera=(), microphone=(), payment=(), usb=(), interest-cohort=()")))
            // The JSON API answers 401 instead of sending the caller to the login page
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor((request, response, e) ->
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Sign in to continue."), API)
                .accessDeniedHandler(accessDeniedHandler()))
            .formLogin(form -> form
                .loginPage("/login")
                .usernameParameter("email")
                .successHandler(successHandler(userService, cartService, loginThrottle))
                .failureHandler(failureHandler(loginThrottle))
                .permitAll())
            .logout(logout -> logout
                .logoutSuccessUrl("/?signedOut")
                // The "Delivers to" location belongs to whoever picked it: signing out goes back to "Select location"
                .deleteCookies("mz_pin", "mz_loc", "mz_addr", "mz_loc_by")
                .permitAll())
            .rememberMe(rm -> rm
                .key(rememberMeKey(rememberMeKey))
                .userDetailsService(userDetailsService)
                .tokenValiditySeconds(14 * 24 * 3600))
            .requestCache(cache -> cache.requestCache(pageVisitsOnly()))
            .sessionManagement(session -> session
                .maximumSessions(-1)            // no limit on concurrent sessions per account
                .sessionRegistry(sessionRegistry)
                .expiredUrl("/login?expired"));
        return http.build();
    }

    /**
     * Admins land on the dashboard; customers go back to what they were doing (checkout, when they signed in from
     * the cart). Anything put in the cart before signing in moves into the account's cart.
     */
    private AuthenticationSuccessHandler successHandler(UserService userService, CartService cartService,
                                                        LoginThrottle loginThrottle) {
        SavedRequestAwareAuthenticationSuccessHandler customer = new SavedRequestAwareAuthenticationSuccessHandler();
        customer.setDefaultTargetUrl("/");
        return (request, response, authentication) -> {
            loginThrottle.succeeded(authentication.getName());
            userService.recordLogin(authentication.getName())
                    .ifPresent(u -> cartService.mergeGuestCart(u, request.getSession(false)));
            boolean staff = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_STAFF"));
            if (staff) response.sendRedirect(request.getContextPath() + "/admin");
            else customer.onAuthenticationSuccess(request, response, authentication);
        };
    }

    /** Admins, or a scraper sending "Authorization: Bearer <MONITORING_TOKEN>" when a token is set. */
    private static AuthorizationManager<RequestAuthorizationContext> adminOrToken(String token) {
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return (authentication, ctx) -> {
            String header = ctx.getRequest().getHeader("Authorization");
            boolean tokenOk = !token.isBlank() && header != null
                    && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8));
            boolean admin = authentication.get() != null && authentication.get().getAuthorities().stream()
                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            return new AuthorizationDecision(tokenOk || admin);
        };
    }

    /**
     * Staff who follow a customer link, or a page outside their role, land on their own home instead of a bare 403.
     * The JSON API always answers 403 (AppErrorController turns it into JSON).
     */
    private AccessDeniedHandler accessDeniedHandler() {
        AccessDeniedHandlerImpl fallback = new AccessDeniedHandlerImpl();
        return (request, response, exception) -> {
            if (API.matches(request)) response.sendError(HttpServletResponse.SC_FORBIDDEN, exception instanceof CsrfException
                    ? "Missing or invalid CSRF token. Reload the page and try again." : "You don't have access to this.");
            else if (request.isUserInRole("STAFF")) response.sendRedirect(request.getContextPath() + "/admin?denied");
            else fallback.handle(request, response, exception);
        };
    }

    private AuthenticationFailureHandler failureHandler(LoginThrottle loginThrottle) {
        return (request, response, exception) -> {
            loginThrottle.failed(request.getParameter("email"), request.getRemoteAddr());
            response.sendRedirect(request.getContextPath()
                    + (exception instanceof DisabledException ? "/login?disabled" : "/login?error"));
        };
    }

    /**
     * What sign-in returns to: only a page the visitor opened (a GET asking for HTML outside /api). Without this, a
     * background request made while signed out (the notification check after a restart ended the session, a site
     * icon) became the place to land after signing in, showing raw JSON or a full-screen icon.
     */
    private static RequestCache pageVisitsOnly() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(new AndRequestMatcher(
                new AntPathRequestMatcher("/**", "GET"),
                new NegatedRequestMatcher(API),
                request -> {
                    String accept = request.getHeader("Accept");
                    return accept != null && accept.contains("text/html");
                }));
        return cache;
    }

    /** Runs before the password is checked, so a locked-out email can't be guessed at, even with the right password. */
    private static OncePerRequestFilter loginThrottleFilter(LoginThrottle loginThrottle) {
        RequestMatcher signIn = new AntPathRequestMatcher("/login", "POST");
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (signIn.matches(request) && loginThrottle.isBlocked(request.getParameter("email"), request.getRemoteAddr())) {
                    response.sendRedirect(request.getContextPath() + "/login?locked");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
    }

    /**
     * Remember-me cookies are signed with this key, so it must be secret: anyone with it and a password hash (from a
     * database backup) could forge a sign-in cookie. Without REMEMBER_ME_KEY a random key is made at startup, which is
     * safe but signs everyone's "Keep me signed in" out on every restart.
     */
    private static String rememberMeKey(String configured) {
        if (!configured.isBlank()) return configured;
        log.warn("REMEMBER_ME_KEY isn't set: using a random key, so \"Keep me signed in\" ends when the app restarts");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Tells the registry when sessions end, so "online now" stays accurate. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
