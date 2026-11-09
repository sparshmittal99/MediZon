package com.medizon.config;

import com.medizon.domain.User;
import com.medizon.repository.UserRepository;
import com.medizon.service.CartService;
import com.medizon.service.NewDeviceAlertService;
import com.medizon.service.SignInService;
import com.medizon.service.TrustedDeviceService;
import com.medizon.service.UserService;
import com.medizon.web.GlobalModelAdvice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/**
 * Everything after a password or a Google account checks out: the 2FA code when the account has it on and this device
 * isn't trusted, then the sign-in itself, kept on this device if asked ("Keep me signed in", always in the app), and
 * what follows every sign-in (cart merge, the device list, the new-device email).
 * <p>
 * While a code is awaited the browser is not signed in: the session only holds who is signing in ({@link Pending}).
 */
@Slf4j
@Component
public class SignInFlow {

    /** Session: who is waiting to enter a code. */
    public static final String PENDING = "mz.2fa.pending";
    /** Request: the password was right but a code is needed, so no remember-me cookie yet (MediZonRememberMeServices). */
    public static final String NEEDS_CODE = "mz.2fa.needsCode";
    /** Session: the "Keep me signed in" box was ticked when Sign in with Google was clicked. */
    public static final String GOOGLE_REMEMBER = "mz.google.remember";

    static final Duration PENDING_FOR = Duration.ofMinutes(10);
    public static final int MAX_TRIES = 5;

    /** Someone who got past the password or Google, waiting to enter a 2FA code. */
    public record Pending(String email, boolean remember, boolean newAccount, Instant at, int tries) implements Serializable {
        Pending tried() { return new Pending(email, remember, newAccount, at, tries + 1); }
        boolean expired() { return at.plus(PENDING_FOR).isBefore(Instant.now()); }
    }

    private final UserRepository users;
    private final AppUserDetailsService userDetails;
    private final MediZonRememberMeServices rememberMe;
    private final SessionRegistry sessionRegistry;
    private final UserService userService;
    private final CartService cartService;
    private final SignInService signIns;
    private final LoginThrottle loginThrottle;
    private final TrustedDeviceService trustedDevices;
    private final NewDeviceAlertService newDeviceAlerts;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public SignInFlow(UserRepository users, AppUserDetailsService userDetails, MediZonRememberMeServices rememberMe,
                      SessionRegistry sessionRegistry, @Lazy UserService userService, @Lazy CartService cartService,
                      @Lazy SignInService signIns, LoginThrottle loginThrottle, TrustedDeviceService trustedDevices,
                      @Lazy NewDeviceAlertService newDeviceAlerts) {
        this.users = users;
        this.userDetails = userDetails;
        this.rememberMe = rememberMe;
        this.sessionRegistry = sessionRegistry;
        this.userService = userService;
        this.cartService = cartService;
        this.signIns = signIns;
        this.loginThrottle = loginThrottle;
        this.trustedDevices = trustedDevices;
        this.newDeviceAlerts = newDeviceAlerts;
    }

    /** Whether signing in to this account on this device takes a 2FA code now. */
    public boolean needsCode(String email, HttpServletRequest request) {
        return users.findByEmailIgnoreCase(email)
                .map(u -> u.isTwoFactorEnabled() && !trustedDevices.isTrusted(u, request))
                .orElse(false);
    }

    /** The sign-in form's password was right (SecurityConfig's success handler). */
    public void afterPassword(HttpServletRequest request, HttpServletResponse response, Authentication auth) throws IOException {
        if (request.getAttribute(NEEDS_CODE) != null) {
            boolean remember = GlobalModelAdvice.isApp(request) || isTicked(request.getParameter("remember-me"));
            awaitCode(request, response, auth.getName(), remember, false);
            return;
        }
        finish(request, auth.getName(), false, response);
        goHome(request, response, auth);
    }

    /** Google vouched for this account (website or app): a 2FA code next if needed, else signed in. */
    public void afterGoogle(HttpServletRequest request, HttpServletResponse response, User user, boolean remember,
                            boolean newAccount) throws IOException {
        if (needsCode(user.getEmail(), request)) {
            awaitCode(request, response, user.getEmail(), remember, newAccount);
            return;
        }
        signIn(request, response, user.getEmail(), remember, newAccount);
    }

    /** The one waiting for a code on this browser, if they haven't run out of time. */
    public Pending pending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(PENDING) instanceof Pending p)) return null;
        if (p.expired()) {
            session.removeAttribute(PENDING);
            return null;
        }
        return p;
    }

    /** A wrong code: returns how many tries are left; at none the sign-in starts over. */
    public int wrongCode(HttpServletRequest request, Pending p) {
        loginThrottle.failed(p.email(), request.getRemoteAddr());   // too many and the password is refused for a while too
        Pending next = p.tried();
        HttpSession session = request.getSession();
        if (next.tries() >= MAX_TRIES) {
            session.removeAttribute(PENDING);
            return 0;
        }
        session.setAttribute(PENDING, next);
        return MAX_TRIES - next.tries();
    }

    public boolean locked(Pending p, HttpServletRequest request) {
        return loginThrottle.isBlocked(p.email(), request.getRemoteAddr());
    }

    /** The right code: signed in, and this device trusted from now on if they asked. */
    public void codeAccepted(HttpServletRequest request, HttpServletResponse response, Pending p, boolean trustDevice)
            throws IOException {
        request.getSession().removeAttribute(PENDING);
        if (trustDevice) users.findByEmailIgnoreCase(p.email()).ifPresent(u -> trustedDevices.trust(u, request, response));
        signIn(request, response, p.email(), p.remember(), p.newAccount());
    }

    /** Forgets a half-finished sign-in ("Use another account"). */
    public void cancel(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.removeAttribute(PENDING);
    }

    // ------------------------------------------------------------------------------------------------

    private void awaitCode(HttpServletRequest request, HttpServletResponse response, String email, boolean remember,
                           boolean newAccount) throws IOException {
        // Not signed in until the code is right: drop what the password or Google sign-in set up
        SecurityContextHolder.clearContext();
        contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        HttpSession session = request.getSession();
        sessionRegistry.removeSessionInformation(session.getId());
        session.setAttribute(PENDING, new Pending(email, remember, newAccount, Instant.now(), 0));
        response.sendRedirect(request.getContextPath() + "/login/verify");
    }

    /** Signs this browser in to the account (after a code, or Google without one). */
    private void signIn(HttpServletRequest request, HttpServletResponse response, String email, boolean remember,
                        boolean newAccount) throws IOException {
        UserDetails details = userDetails.loadUserByUsername(email);
        if (!details.isEnabled()) {
            SecurityContextHolder.clearContext();
            contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
            response.sendRedirect(request.getContextPath() + "/login?disabled");
            return;
        }
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());

        // A new session id at sign-in, so one planted before can't be used (as form sign-in does)
        HttpSession session = request.getSession();
        String oldId = session.getId();
        request.changeSessionId();
        sessionRegistry.removeSessionInformation(oldId);
        sessionRegistry.removeSessionInformation(session.getId());
        sessionRegistry.registerNewSession(request.getSession().getId(), details);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);

        if (remember || GlobalModelAdvice.isApp(request)) rememberMe.onLoginSuccess(request, response, auth);
        finish(request, email, newAccount, response);
        goHome(request, response, auth);
    }

    /** What happens after every sign-in, however it was made. */
    private void finish(HttpServletRequest request, String email, boolean newAccount, HttpServletResponse response) {
        loginThrottle.succeeded(email);
        userService.recordLogin(email).ifPresent(u -> {
            SignInService.started(request.getSession(), u.getRememberEpoch());
            signIns.signedInAgain(u, request);    // in case this device was signed out from another one
            cartService.mergeGuestCart(u, request.getSession(false));
            try {
                newDeviceAlerts.signedIn(u, request, response, newAccount);
            } catch (RuntimeException e) {
                log.warn("New sign-in check failed for account {}: {}", u.getId(), e.getMessage());   // never blocks signing in
            }
        });
    }

    /**
     * Admins land on the dashboard; customers go back to what they were doing (checkout, when they signed in from
     * the cart).
     */
    private static void goHome(HttpServletRequest request, HttpServletResponse response, Authentication auth) throws IOException {
        boolean staff = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_STAFF"));
        if (staff) {
            response.sendRedirect(request.getContextPath() + "/admin");
            return;
        }
        SavedRequestAwareAuthenticationSuccessHandler customer = new SavedRequestAwareAuthenticationSuccessHandler();
        customer.setDefaultTargetUrl("/");
        try {
            customer.onAuthenticationSuccess(request, response, auth);
        } catch (jakarta.servlet.ServletException e) {
            throw new IOException(e);
        }
    }

    private static boolean isTicked(String value) {
        return value != null && (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes") || value.equals("1"));
    }
}
