package com.medizon.api;

import com.medizon.service.CurrentUser;
import com.medizon.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Step one of the change-password screen: checks the current password before the new one is asked for. The change
 * itself (AccountController) checks it again. After five wrong tries the check pauses for five minutes.
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountApi {

    private static final int MAX_TRIES = 5;
    private static final long PAUSE_SECONDS = 300;
    private static final String TRIES = "mzPasswordTries";
    private static final String PAUSED_UNTIL = "mzPasswordPausedUntil";

    private final UserService userService;
    private final CurrentUser currentUser;

    public record VerifyRequest(String password) {}

    @PostMapping("/verify-password")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody VerifyRequest req, HttpSession session) {
        if (session.getAttribute(PAUSED_UNTIL) instanceof Instant until && Instant.now().isBefore(until)) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many wrong tries. Wait a few minutes and try again."));
        }
        if (userService.checkPassword(currentUser.get(), req.password())) {
            session.removeAttribute(TRIES);
            session.removeAttribute(PAUSED_UNTIL);
            return ResponseEntity.ok(Map.of("ok", true));
        }
        int tries = session.getAttribute(TRIES) instanceof Integer n ? n + 1 : 1;
        if (tries >= MAX_TRIES) {
            session.setAttribute(PAUSED_UNTIL, Instant.now().plusSeconds(PAUSE_SECONDS));
            session.removeAttribute(TRIES);
            return ResponseEntity.status(429).body(Map.of("error", "Too many wrong tries. Wait a few minutes and try again."));
        }
        session.setAttribute(TRIES, tries);
        return ResponseEntity.badRequest().body(Map.of("error", "That isn't your current password."));
    }
}
