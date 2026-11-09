package com.medizon.service;

import com.medizon.domain.NotificationType;
import com.medizon.domain.SecureAccountToken;
import com.medizon.domain.User;
import com.medizon.repository.SecureAccountTokenRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The "This wasn't me" link in a new sign-in email. Opening it shows what signed in and one button; the button signs
 * the account out on every device, makes the old password stop working, and (for customers) goes straight to choosing
 * a new one. Staff ask the admin for a new password, as they always do.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecureAccountService {

    private final SecureAccountTokenRepository tokens;
    private final UserRepository users;
    private final UserService userService;
    private final PasswordResetService passwordReset;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notifications;

    /** A new link for an email; returns the token that goes in it (only its hash is kept). */
    @Transactional
    public String issue(User user, String deviceLabel) {
        tokens.deleteExpired(LocalDateTime.now());
        String raw = Tokens.random();
        SecureAccountToken t = new SecureAccountToken();
        t.setUser(user);
        t.setTokenHash(Tokens.sha256(raw));
        t.setDeviceLabel(deviceLabel == null ? null : deviceLabel.length() > 60 ? deviceLabel.substring(0, 60) : deviceLabel);
        tokens.save(t);
        return raw;
    }

    /** The link's token, while it's unused and in date. */
    @Transactional(readOnly = true)
    public Optional<SecureAccountToken> find(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return tokens.findByTokenHash(Tokens.sha256(raw))
                .filter(t -> !t.isExpired())
                .filter(t -> !t.getUser().isDeleted() && t.getUser().isEnabled());
    }

    /** What the button did: a password-reset token for customers to choose a new password; null for staff. */
    public record Secured(User user, String resetToken) {}

    @Transactional
    public Secured secure(String raw) {
        SecureAccountToken t = find(raw).orElseThrow(() ->
                new BusinessException("This link has expired or was already used. To be safe, reset your password from the sign-in page."));
        User u = users.findById(t.getUser().getId()).orElseThrow(() -> new NotFoundException("User not found."));
        tokens.deleteAllOf(u);

        // Whoever signed in can't come back with the same password; then every device is signed out
        u.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        users.save(u);
        int devices = userService.signOutEverywhere(u);
        log.warn("Account {} secured from a new sign-in email: signed out on {} device(s)", u.getId(), devices);
        try {
            notifications.notify(u, NotificationType.INFO, "Your account was secured",
                    "You were signed out on every device after reporting a sign-in that wasn't you. Choose a new password to sign in again.",
                    "/account");
        } catch (Exception e) {
            log.warn("Could not notify account {}: {}", u.getId(), e.getMessage());
        }
        return new Secured(u, u.isCustomer() ? passwordReset.verifiedTokenFor(u) : null);
    }
}
