package com.medizon.config;

import com.medizon.repository.UserRepository;
import com.medizon.web.GlobalModelAdvice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

/**
 * "Keep me signed in". On the website it's the login checkbox and lasts {@value #WEB_DAYS} days. In the Android app
 * there's no checkbox: every sign-in is remembered for years, so the app stays signed in until the user signs out
 * or clears the app's data.
 * <p>
 * The token's signature also covers the account's {@code rememberEpoch}: "Sign out other devices" and a password
 * change bump it, which ends every remembered sign-in at once; the device that asked gets a new one ({@link #reissue}).
 */
public class MediZonRememberMeServices extends TokenBasedRememberMeServices {

    static final int WEB_DAYS = 14;
    /** Effectively "until signed out"; well within a cookie's limits. */
    static final int APP_SECONDS = 10 * 365 * 24 * 3600;

    private final UserRepository users;

    public MediZonRememberMeServices(String key, UserDetailsService userDetailsService, UserRepository users) {
        super(key, userDetailsService);
        this.users = users;
        setTokenValiditySeconds(WEB_DAYS * 24 * 3600);
    }

    @Override
    protected boolean rememberMeRequested(HttpServletRequest request, String parameter) {
        return GlobalModelAdvice.isApp(request) || super.rememberMeRequested(request, parameter);
    }

    @Override
    protected int calculateLoginLifetime(HttpServletRequest request, Authentication authentication) {
        return GlobalModelAdvice.isApp(request) ? APP_SECONDS : getTokenValiditySeconds();
    }

    @Override
    protected String makeTokenSignature(long tokenExpiryTime, String username, String password) {
        return super.makeTokenSignature(tokenExpiryTime, username, withEpoch(username, password));
    }

    @Override
    protected String makeTokenSignature(long tokenExpiryTime, String username, String password, RememberMeTokenAlgorithm algorithm) {
        return super.makeTokenSignature(tokenExpiryTime, username, withEpoch(username, password), algorithm);
    }

    /**
     * After the account's remembered sign-ins were ended: keeps this device remembered if it was (the app always is),
     * with a token for the new epoch.
     */
    public void reissue(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication == null) return;
        if (GlobalModelAdvice.isApp(request) || extractRememberMeCookie(request) != null) {
            // Signed with the account as it is now (a password change has just replaced the hash)
            UserDetails fresh = getUserDetailsService().loadUserByUsername(authentication.getName());
            onLoginSuccess(request, response,
                    UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities()));
        }
    }

    private String withEpoch(String username, String password) {
        int epoch = users.findByEmailIgnoreCase(username).map(u -> u.getRememberEpoch()).orElse(0);
        return password + ":" + epoch;
    }
}
