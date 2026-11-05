package com.medizon.config;

import com.medizon.repository.UserRepository;
import com.medizon.repository.UserRepository.AccountState;
import com.medizon.service.SignInService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Checks every signed-in request against the account as it is now. A disabled or deleted account, or a session from
 * before "Sign out other devices", a password change or an admin's sign-out, is signed out on the spot.
 * <p>
 * The session registry can't do this alone: a session restored from a remember-me cookie (how the Android app stays
 * signed in) is never added to it, so expiring the registry's sessions missed every phone. Also records where the
 * account is signed in (SignInService).
 * <p>
 * Not a bean: Spring Boot would also register it as a plain servlet filter. SecurityConfig adds it to the chain.
 */
public class SignInGuard extends OncePerRequestFilter {

    /** Files that need no check (a page loads many of them). */
    private static final List<String> STATIC = List.of("/css/", "/js/", "/images/", "/medicine-images/", "/fonts/",
            "/favicon", "/apple-touch-icon", "/actuator/health", "/api/monitoring/");

    private final UserRepository users;
    private final SignInService signIns;
    private final MediZonRememberMeServices rememberMe;

    public SignInGuard(UserRepository users, SignInService signIns, MediZonRememberMeServices rememberMe) {
        this.users = users;
        this.signIns = signIns;
        this.rememberMe = rememberMe;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return STATIC.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AccountState> state = users.findStateByEmailIgnoreCase(auth.getName());
        boolean disabled = state.isEmpty() || !state.get().isEnabled() || state.get().isDeleted();
        HttpSession session = request.getSession(false);
        boolean ended = !disabled && session != null && SignInService.outdated(session, state.get().getRememberEpoch());
        if (disabled || ended) {
            signOut(request, response);
            // Like the session registry's expiry: fetch() calls see a redirect and the page goes to the sign-in page
            response.sendRedirect(request.getContextPath() + (disabled ? "/login?disabled" : "/login?expired"));
            return;
        }
        signIns.seen(state.get().getId(), request, response, rememberMe.rememberedUntil(request).orElse(null));
        chain.doFilter(request, response);
    }

    private void signOut(HttpServletRequest request, HttpServletResponse response) {
        rememberMe.loginFail(request, response);    // clears the remember-me cookie
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
