package com.medizon.web;

import com.medizon.domain.Address;
import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.DeliveryAreaService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The "Delivers to" location in the header. It's kept in a cookie so it lasts across visits, signed in or not;
 * a signed-in customer who hasn't picked one sees their profile's PIN code.
 */
@Component
@RequiredArgsConstructor
public class DeliveryLocations {

    static final String PIN_COOKIE = "mz_pin";
    static final String LABEL_COOKIE = "mz_loc";
    /** The saved address picked in the header; checkout starts on it. Cleared when a bare PIN code is picked. */
    static final String ADDRESS_COOKIE = "mz_addr";
    /** Who picked the location: a user id, or empty for a signed-out visitor. Others on the same browser don't see it. */
    static final String OWNER_COOKIE = "mz_loc_by";
    private static final Duration KEEP_FOR = Duration.ofDays(180);

    private final DeliveryAreaService deliveryAreas;
    private final CurrentUser currentUser;

    /** A PIN code, what to call it ("Bandra West, Mumbai"), and whether MediZon delivers there. */
    @Value
    public static class Location {
        String pincode;
        String label;
        boolean serviceable;
    }

    public Optional<Location> current(HttpServletRequest request, User user) {
        boolean mine = pickedBy(request, user);
        String pin = mine ? DeliveryAreaService.normalize(cookie(request, PIN_COOKIE)) : null;
        String label = mine ? cookie(request, LABEL_COOKIE) : null;
        if (pin == null && user != null && !user.isAdmin()) {
            pin = DeliveryAreaService.normalize(user.getPincode());
            label = user.getCity();
        }
        if (pin == null) return Optional.empty();
        return Optional.of(locate(pin, label));
    }

    /** Works out a location for a PIN code; the label falls back to the area admins gave that PIN code. */
    public Location locate(String pincode, String label) {
        String pin = DeliveryAreaService.normalize(pincode);
        String name = label == null || label.isBlank() ? null : label.trim();
        if (name == null) {
            name = deliveryAreas.find(pin)
                    .map(s -> s.getArea() != null ? s.getArea() + (s.getCity() != null ? ", " + s.getCity() : "") : s.getCity())
                    .orElse(null);
        }
        if (name != null && name.length() > 60) name = name.substring(0, 60);
        return new Location(pin, name, deliveryAreas.isServiceable(pin));
    }

    public void remember(Location location, HttpServletRequest request, HttpServletResponse response) {
        set(response, request, OWNER_COOKIE, currentUser.find().map(u -> String.valueOf(u.getId())).orElse(""));
        set(response, request, PIN_COOKIE, location.getPincode());
        set(response, request, LABEL_COOKIE, location.getLabel() == null ? "" : location.getLabel());
        set(response, request, ADDRESS_COOKIE, "");
    }

    /** Delivers to a saved address: its PIN code shows in the header and checkout starts on it. */
    public Location chooseAddress(Address a, HttpServletRequest request, HttpServletResponse response) {
        Location loc = locate(a.getPincode(), a.getLabel() + " · " + a.getCity());
        remember(loc, request, response);
        set(response, request, ADDRESS_COOKIE, String.valueOf(a.getId()));
        return loc;
    }

    /** The saved address last picked in the header, if any. */
    public Long selectedAddressId(HttpServletRequest request) {
        if (!pickedBy(request, currentUser.find().orElse(null))) return null;
        String v = cookie(request, ADDRESS_COOKIE);
        try {
            return v == null || v.isBlank() ? null : Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether the remembered location may be shown to this person: they picked it themselves, or a signed-out visitor
     * picked it on this browser and has now signed in. A signed-in user's choice never shows to anyone else.
     */
    private static boolean pickedBy(HttpServletRequest request, User user) {
        String owner = cookie(request, OWNER_COOKIE);
        if (owner == null || owner.isEmpty()) return true;
        return user != null && owner.equals(String.valueOf(user.getId()));
    }

    private static void set(HttpServletResponse response, HttpServletRequest request, String name, String value) {
        String path = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
        ResponseCookie c = ResponseCookie.from(name, URLEncoder.encode(value, StandardCharsets.UTF_8))
                .path(path).maxAge(KEEP_FOR).httpOnly(true).sameSite("Lax").build();
        response.addHeader("Set-Cookie", c.toString());
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies).filter(c -> c.getName().equals(name)).findFirst()
                .map(c -> URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8)).orElse(null);
    }
}
