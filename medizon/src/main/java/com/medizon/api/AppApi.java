package com.medizon.api;

import com.medizon.domain.DoseStatus;
import com.medizon.domain.NotificationDevice;
import com.medizon.domain.User;
import com.medizon.service.AppScheduleService;
import com.medizon.service.BusinessException;
import com.medizon.service.CurrentUser;
import com.medizon.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * For the Android app. {@code /link} and {@code /unlink} are called from signed-in pages inside the app; the
 * schedule and dose answers come from the app's background sync and alarm screen with
 * {@code Authorization: Bearer <sync token>}, no session or CSRF token.
 */
@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
public class AppApi {

    private final AppScheduleService app;
    private final DeviceService deviceService;
    private final CurrentUser currentUser;

    /** A sync token for this device (the app's WebView), and the account it belongs to. */
    @PostMapping("/link")
    public Map<String, Object> link(HttpServletRequest request, HttpServletResponse response) {
        User user = currentUser.get();
        NotificationDevice device = deviceService.current(user, request, response);
        return Map.of("token", app.link(device), "account", user.getId().toString());
    }

    @PostMapping("/unlink")
    public Map<String, Object> unlink(HttpServletRequest request) {
        NotificationDevice device = deviceService.find(currentUser.get(), request);
        if (device != null) app.unlink(device);
        return Map.of("ok", true);
    }

    @GetMapping("/schedule")
    public ResponseEntity<?> schedule(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        Optional<NotificationDevice> device = app.device(bearer(auth));
        if (device.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not linked."));
        return ResponseEntity.ok(app.schedule(device.get(), LocalDateTime.now().withNano(0)));
    }

    public record PushToken(String token) {}

    /** The app's Firebase token for phone notifications; an empty token turns them off for this phone. */
    @PostMapping("/push-token")
    public ResponseEntity<?> pushToken(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                       @RequestBody PushToken req) {
        Optional<NotificationDevice> device = app.device(bearer(auth));
        if (device.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not linked."));
        String token = req == null || req.token() == null ? "" : req.token().trim();
        if (token.length() > 512) throw new BusinessException("That token is too long.");
        app.setPushToken(device.get(), token.isEmpty() ? null : token);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    public record DoseAnswer(Long planId, String at, String status) {}

    @PostMapping("/doses")
    public ResponseEntity<?> dose(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                  @RequestBody DoseAnswer req) {
        Optional<NotificationDevice> device = app.device(bearer(auth));
        if (device.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not linked."));
        if (req.planId() == null || req.at() == null) throw new BusinessException("Plan and time are required.");
        DoseStatus status = "SKIPPED".equalsIgnoreCase(req.status()) ? DoseStatus.SKIPPED
                : "TAKEN".equalsIgnoreCase(req.status()) ? DoseStatus.TAKEN : null;
        if (status == null) throw new BusinessException("Status is TAKEN or SKIPPED.");
        LocalDateTime at;
        try {
            at = OffsetDateTime.parse(req.at()).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new BusinessException("Time isn't in ISO format.");
        }
        app.answer(device.get(), req.planId(), at, status);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String bearer(String header) {
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
    }
}
