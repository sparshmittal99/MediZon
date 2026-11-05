package com.medizon.service;

import com.medizon.domain.Member;
import com.medizon.domain.NotificationDevice;
import com.medizon.domain.User;
import com.medizon.repository.NotificationDeviceRepository;
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
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Per-browser notification settings. A long-lived cookie identifies the browser; each account signed in on it has
 * its own row, so a shared family tablet can follow different members for different accounts.
 */
@Service
@RequiredArgsConstructor
public class DeviceService {

    public static final String COOKIE = "mz_device";
    private static final Duration COOKIE_LIFETIME = Duration.ofDays(730);

    private final NotificationDeviceRepository devices;
    private final MemberService memberService;

    /** This browser's settings for the user, created (with the cookie) on the first page view. */
    @Transactional
    public NotificationDevice current(User user, HttpServletRequest request, HttpServletResponse response) {
        String finalKey = key(request, response);
        NotificationDevice d = devices.findByUserAndDeviceKey(user, finalKey).orElseGet(() -> {
            NotificationDevice n = new NotificationDevice();
            n.setUser(user);
            n.setDeviceKey(finalKey);
            n.setLabel(describe(request.getHeader(HttpHeaders.USER_AGENT)));
            return devices.save(n);
        });
        if (d.getLastSeenAt() == null || d.getLastSeenAt().isBefore(LocalDateTime.now().minusMinutes(10))) {
            d.setLastSeenAt(LocalDateTime.now());
        }
        return d;
    }

    /** Lookup for API calls, which never create anything; null means no settings yet, so everything shows. */
    @Transactional(readOnly = true)
    public NotificationDevice find(User user, HttpServletRequest request) {
        String key = cookie(request);
        return key == null ? null : devices.findByUserAndDeviceKey(user, key).orElse(null);
    }

    public List<NotificationDevice> forUser(User user) { return devices.findByUserOrderByLastSeenAtDesc(user); }

    @Transactional
    public NotificationDevice save(User user, Long deviceId, String label, boolean allMembers, Collection<Long> memberIds) {
        NotificationDevice d = devices.findByIdAndUser(deviceId, user)
                .orElseThrow(() -> new BusinessException("That device isn't linked to your account."));
        if (label != null && !label.isBlank()) d.setLabel(label.trim().length() > 60 ? label.trim().substring(0, 60) : label.trim());
        d.setAllMembers(allMembers);
        d.getMembers().clear();
        if (!allMembers && memberIds != null) {
            for (Long id : memberIds) {
                Member m = memberService.get(id, user);
                d.getMembers().add(m);
            }
        }
        d.setConfigured(true);
        return d;
    }

    @Transactional
    public void forget(Long deviceId, User user) {
        devices.findByIdAndUser(deviceId, user).ifPresent(devices::delete);
    }

    /**
     * This browser's key, given the cookie on its first visit. The same key names its sign-in (SignInService).
     * Made once per request, so a filter and a page in the same request don't each set a different cookie.
     */
    public static String key(HttpServletRequest request, HttpServletResponse response) {
        String key = cookie(request);
        if (key != null) return key;
        Object made = request.getAttribute(COOKIE);
        if (made instanceof String s) return s;
        key = UUID.randomUUID().toString();
        request.setAttribute(COOKIE, key);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, key)
                .path(contextPath(request)).maxAge(COOKIE_LIFETIME).httpOnly(true).sameSite("Lax")
                .secure(request.isSecure()).build().toString());
        return key;
    }

    /** This browser's key, or null before it has one. */
    public static String keyOf(HttpServletRequest request) {
        String key = cookie(request);
        return key != null ? key : request.getAttribute(COOKIE) instanceof String s ? s : null;
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && c.getValue().matches("[0-9a-f-]{36}")) return c.getValue();
        }
        return null;
    }

    private static String contextPath(HttpServletRequest request) {
        String p = request.getContextPath();
        return p == null || p.isEmpty() ? "/" : p;
    }

    /** "Chrome on Windows" from a User-Agent header; good enough to tell a family's devices apart. */
    static String describe(String ua) {
        if (ua == null) return "This device";
        if (ua.contains("MediZonApp")) return "MediZon app on Android";
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") ? "Opera" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : "Browser";
        String os = ua.contains("Android") ? "Android" : ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad"
                : ua.contains("Windows") ? "Windows" : ua.contains("Mac OS") ? "Mac" : ua.contains("Linux") ? "Linux" : null;
        return os == null ? browser : browser + " on " + os;
    }
}
