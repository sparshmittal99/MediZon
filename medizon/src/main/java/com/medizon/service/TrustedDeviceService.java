package com.medizon.service;

import com.medizon.domain.TrustedDevice;
import com.medizon.domain.User;
import com.medizon.repository.TrustedDeviceRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * "Don't ask again on this device" for 2FA. The device keeps two cookies: mz_device (which device it is, shared with
 * the sign-in list) and mz_trust (a random secret, one per browser, whose hash is kept for each account that trusts
 * it). Both have to match. Ending trust is done by SignInService and TwoFactorService.
 */
@Service
@RequiredArgsConstructor
public class TrustedDeviceService {

    public static final String COOKIE = "mz_trust";
    private static final Duration COOKIE_LIFETIME = Duration.ofDays(3650);

    private final TrustedDeviceRepository trusted;

    /** True when this browser is trusted for the account, so its sign-in skips the code. */
    @Transactional
    public boolean isTrusted(User user, HttpServletRequest request) {
        String key = DeviceService.keyOf(request);
        String token = cookie(request);
        if (key == null || token == null) return false;
        Optional<TrustedDevice> t = trusted.findByUser_IdAndDeviceKey(user.getId(), key)
                .filter(d -> Tokens.sameHash(d.getTokenHash(), Tokens.sha256(token)));
        t.ifPresent(d -> d.setLastUsedAt(LocalDateTime.now()));
        return t.isPresent();
    }

    /** Trusts this browser for the account from now on. */
    @Transactional
    public void trust(User user, HttpServletRequest request, HttpServletResponse response) {
        String key = DeviceService.key(request, response);
        String token = cookie(request);
        if (token == null) {
            token = Tokens.random();
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, token)
                    .path(contextPath(request)).maxAge(COOKIE_LIFETIME).httpOnly(true).sameSite("Lax")
                    .secure(request.isSecure()).build().toString());
        }
        TrustedDevice d = trusted.findByUserAndDeviceKey(user, key).orElseGet(TrustedDevice::new);
        d.setUser(user);
        d.setDeviceKey(key);
        d.setTokenHash(Tokens.sha256(token));
        d.setLabel(DeviceService.describe(request.getHeader(HttpHeaders.USER_AGENT)));
        d.setCreatedAt(LocalDateTime.now());
        d.setLastUsedAt(LocalDateTime.now());
        trusted.save(d);
    }

    /** No device is trusted for the account any more: 2FA turned off or reset, or signed out everywhere. */
    @Transactional
    public void forgetAll(User user) { trusted.deleteAllOf(user); }

    /** Every device but this one stops being trusted ("Sign out other devices", a password change). */
    @Transactional
    public void forgetAllExcept(User user, String keepKey) {
        if (keepKey == null) trusted.deleteAllOf(user);
        else trusted.deleteAllExcept(user, keepKey);
    }

    /** One device signed out from See devices. */
    @Transactional
    public void forget(User user, String deviceKey) { trusted.deleteDevice(user, deviceKey); }

    public long count(User user) { return trusted.countByUser(user); }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && c.getValue().matches("[A-Za-z0-9_-]{43}")) return c.getValue();
        }
        return null;
    }

    private static String contextPath(HttpServletRequest request) {
        String p = request.getContextPath();
        return p == null || p.isEmpty() ? "/" : p;
    }
}
