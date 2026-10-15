package com.medizon.config;

import com.medizon.service.CartService;
import com.medizon.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
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
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Every sign-in gets its own HTTP session, so any number of customers and admins can be signed in
     * at once (one per browser or private window). The registry lets admins see who is online and
     * sign a user out everywhere.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AppUserDetailsService userDetailsService,
                                           SessionRegistry sessionRegistry, @Lazy UserService userService,
                                           @Lazy CartService cartService,
                                           @Value("${medizon.monitoring.token:}") String monitoringToken) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Monitoring: health for load balancers and uptime checks; Prometheus with a bearer token or as an admin
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/prometheus").access(adminOrToken(monitoringToken))
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // Browsers report JavaScript errors and page load times, signed in or not
                .requestMatchers("/api/monitoring/**").permitAll()
                .requestMatchers("/", "/medicines", "/medicines/**", "/help", "/register", "/login",
                        "/css/**", "/js/**", "/images/**", "/medicine-images/**", "/favicon.ico", "/favicon.svg", "/apple-touch-icon.png", "/error").permitAll()
                // Visitors can fill a cart and pick a delivery location before signing in; checkout needs an account.
                // Admin accounts are turned away in CartController / CartService.
                .requestMatchers("/cart", "/cart/add", "/cart/*/remove", "/api/cart", "/api/cart/*", "/api/location/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/prescriptions/*/file", "/returns/*/images/*").authenticated()   // owner or admin, checked in the service
                // Shopping and dose reminders are for customers; admin accounts only run the store.
                // The assistant API is open to both roles; each role gets its own actions in AssistantTools.
                .requestMatchers("/cart/**", "/api/cart/**", "/checkout", "/orders/**", "/plans/**",
                        "/prescriptions/**", "/members/**", "/addresses/**", "/billing-address/**", "/notifications/settings", "/notifications/devices/**").hasRole("CUSTOMER")
                .anyRequest().authenticated())
            // sendBeacon can't send the CSRF header; these endpoints only record telemetry
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/monitoring/**"))
            .exceptionHandling(ex -> ex.accessDeniedHandler(accessDeniedHandler()))
            .formLogin(form -> form
                .loginPage("/login")
                .usernameParameter("email")
                .successHandler(successHandler(userService, cartService))
                .failureHandler(failureHandler())
                .permitAll())
            .logout(logout -> logout
                .logoutSuccessUrl("/?signedOut")
                // The "Delivers to" location belongs to whoever picked it: signing out goes back to "Select location"
                .deleteCookies("mz_pin", "mz_loc", "mz_addr", "mz_loc_by")
                .permitAll())
            .rememberMe(rm -> rm
                .key("medizon-remember-me")
                .userDetailsService(userDetailsService)
                .tokenValiditySeconds(14 * 24 * 3600))
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
    private AuthenticationSuccessHandler successHandler(UserService userService, CartService cartService) {
        SavedRequestAwareAuthenticationSuccessHandler customer = new SavedRequestAwareAuthenticationSuccessHandler();
        customer.setDefaultTargetUrl("/");
        return (request, response, authentication) -> {
            userService.recordLogin(authentication.getName())
                    .ifPresent(u -> cartService.mergeGuestCart(u, request.getSession(false)));
            boolean admin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            if (admin) response.sendRedirect(request.getContextPath() + "/admin");
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

    /** An admin who follows a customer link lands back on the dashboard instead of a bare 403. */
    private AccessDeniedHandler accessDeniedHandler() {
        AccessDeniedHandlerImpl fallback = new AccessDeniedHandlerImpl();
        return (request, response, exception) -> {
            if (request.isUserInRole("ADMIN")) response.sendRedirect(request.getContextPath() + "/admin");
            else fallback.handle(request, response, exception);
        };
    }

    private AuthenticationFailureHandler failureHandler() {
        return (request, response, exception) -> response.sendRedirect(request.getContextPath()
                + (exception instanceof DisabledException ? "/login?disabled" : "/login?error"));
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
