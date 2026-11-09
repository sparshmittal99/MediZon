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
        String better = renamed(d.getLabel(), request.getHeader(HttpHeaders.USER_AGENT));
        if (better != null) d.setLabel(better);
        return d;
    }

    public static final String PHONE = "PHONE", TABLET = "TABLET", COMPUTER = "COMPUTER";

    /**
     * Phone, tablet or computer, from a User-Agent header. Phones' browsers (and the app's WebView) say "Mobile";
     * Android tablets don't; iPads say iPad (or, in desktop mode, pass for a Mac).
     */
    public static String kind(String ua) {
        if (ua == null) return COMPUTER;
        if (ua.contains("iPad") || ua.contains("Tablet")) return TABLET;
        if (ua.contains("iPhone") || ua.contains("Mobile")) return PHONE;
        if (ua.contains("Android")) return TABLET;
        return COMPUTER;
    }

    /** What the Android app was called before it sent its phone's name. */
    static final String OLD_APP_LABEL = "MediZon app on Android";

    /**
     * The phone's name for a device still called {@link #OLD_APP_LABEL}, once its app sends one; null to keep the
     * label (anything the user renamed it to is never touched).
     */
    static String renamed(String label, String ua) {
        if (!OLD_APP_LABEL.equals(label)) return null;
        String now = describe(ua);
        return now.equals(label) ? null : now;
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

    /**
     * "Chrome on Windows" from a User-Agent header; good enough to tell a family's devices apart. The Android app
     * sends its phone's name ("MediZonDevice/Sparsh%27s%20Galaxy%20S23"), which is used as is.
     */
    static String describe(String ua) {
        if (ua == null) return "This device";
        if (ua.contains("MediZonApp")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("MediZonDevice/(\\S+)").matcher(ua);
            if (m.find()) {
                try {
                    String name = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8)
                            .replaceAll("\\p{Cntrl}", "").trim();
                    if (!name.isEmpty()) return name.length() > 60 ? name.substring(0, 60) : name;
                } catch (IllegalArgumentException badEncoding) {
                    // fall through to the generic name
                }
            }
            return OLD_APP_LABEL;
        }
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") ? "Opera" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : "Browser";
        String os = ua.contains("Android") ? "Android" : ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad"
                : ua.contains("Windows") ? "Windows" : ua.contains("Mac OS") ? "Mac" : ua.contains("Linux") ? "Linux" : null;
        return os == null ? browser : browser + " on " + os;
    }
}
