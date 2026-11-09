package com.medizon.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Writes and sends the new sign-in email, in the background so the IP lookup never slows signing in. */
@Component
@RequiredArgsConstructor
public class NewDeviceMailer {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' h:mm a", Locale.ENGLISH);

    /** {@code at} is the server's local time. */
    public record Alert(String to, String firstName, String device, LocalDateTime at, String ip, String secureToken) {}

    private final EmailService email;
    private final GeoIpClient geoIp;

    @Value("${medizon.base-url:http://localhost:8080}")
    private String baseUrl;

    @Async
    public void send(Alert a) {
        if (a.to() == null || a.to().isBlank()) return;
        String base = baseUrl.replaceAll("/+$", "");
        String when = a.at().atZone(ZoneId.systemDefault()).withZoneSameInstant(INDIA).format(WHEN) + " IST";
        String place = geoIp.place(a.ip()).orElse(null);
        String secureUrl = base + "/account/secure?token=" + a.secureToken();
        String name = a.firstName() == null || a.firstName().isBlank() ? "there" : a.firstName();

        Map<String, Object> v = new HashMap<>();
        v.put("firstName", name);
        v.put("device", a.device());
        v.put("when", when);
        v.put("place", place);
        v.put("email", a.to());
        v.put("secureUrl", secureUrl);
        v.put("devicesUrl", base + "/account/devices");
        v.put("baseUrl", base);

        String text = "Hi " + name + ",\n\n"
                + "Your MediZon account (" + a.to() + ") was just signed in on a new device.\n\n"
                + "Device: " + a.device() + "\n"
                + "When: " + when + "\n"
                + "Near: " + (place != null ? place + " (approximate, from the internet address)" : "Location unavailable") + "\n"
                + "\nIf this was you, you don't need to do anything.\n\n"
                + "If this wasn't you, someone else may know your password. Secure your account now: this signs you out "
                + "everywhere and asks you for a new password:\n" + secureUrl + "\n\n"
                + "MediZon will never ask for your password, OTP or 2FA code by phone, chat or email.\n\n"
                + "MediZon Pharmacy Team";
        email.sendTemplate(a.to(), "New sign-in to your MediZon account on " + a.device(), "emails/new-sign-in", v, text);
    }
}
