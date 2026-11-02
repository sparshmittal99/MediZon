package com.medizon.service;

import com.medizon.domain.PasswordResetToken;
import com.medizon.domain.User;
import com.medizon.repository.PasswordResetTokenRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles password reset requests:
 * 1. Issues 15-minute 6-digit verification codes sent via email
 * 2. Validates verification code and issues a temporary verified reset token
 * 3. Updates the user's password and invalidates active sessions
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final EmailService emailService;
    private final NotificationService notificationService;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${medizon.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * Customer requested a password reset. For security, never disclose whether an email
     * exists in the database. If found, generate a 6-digit verification code and email it.
     */
    @Transactional
    public void initiateReset(String email) {
        if (email == null || email.isBlank()) return;
        String cleanEmail = email.trim().toLowerCase();

        // Housekeeping: clean expired tokens
        tokenRepository.deleteExpiredBefore(LocalDateTime.now());

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(cleanEmail);
        if (userOpt.isEmpty()) {
            log.info("Password reset requested for non-existent email: {}", cleanEmail);
            return;
        }

        User user = userOpt.get();
        if (user.isDeleted() || !user.isEnabled()) {
            log.warn("Password reset requested for disabled/deleted account: {}", cleanEmail);
            return;
        }

        if (!user.isCustomer()) {
            log.info("Password reset requested for staff/admin account: {}", cleanEmail);
            // Staff and admin passwords are managed by administrators via Admin > Users
            return;
        }

        // A code went out moments ago: don't send another until the cooldown has passed
        LocalDateTime cooldownEdge = LocalDateTime.now()
                .plusMinutes(PasswordResetToken.EXPIRY_MINUTES)
                .minusSeconds(PasswordResetToken.RESEND_COOLDOWN_SECONDS);
        if (tokenRepository.existsByUserAndExpiresAtAfter(user, cooldownEdge)) {
            log.info("Password reset code for {} requested again within the cooldown; not resent", cleanEmail);
            return;
        }

        // Delete any old tokens for this user
        tokenRepository.deleteByUser(user);

        // Generate a 6-digit numeric verification code
        int codeInt = 100_000 + secureRandom.nextInt(900_000);
        String code = String.valueOf(codeInt);

        PasswordResetToken token = new PasswordResetToken(code, user);
        tokenRepository.save(token);

        Map<String, Object> variables = Map.of(
                "user", user,
                "code", code,
                "expiryMinutes", PasswordResetToken.EXPIRY_MINUTES,
                "baseUrl", baseUrl
        );

        String plainText = "Hi " + user.getFirstName() + ",\n\n"
                + "Your MediZon verification code to reset your password is: " + code + "\n\n"
                + "This code is valid for " + PasswordResetToken.EXPIRY_MINUTES + " minutes. Do not share this code with anyone.\n\n"
                + "Regards,\nMediZon Pharmacy Team";

        emailService.sendTemplate(user.getEmail(), "Your MediZon Verification Code: " + code, "emails/forgot-password", variables, plainText);
        log.info("Password reset verification code sent to {}", cleanEmail);
    }

    /**
     * Validates the 6-digit code for the user. If valid, replaces the code with a verified reset token
     * and returns it so the customer can proceed to the new password screen.
     */
    @Transactional
    public String verifyCode(String email, String code) {
        if (email == null || email.isBlank()) {
            throw new BusinessException("Please enter your email address.");
        }
        if (code == null || code.trim().length() != 6) {
            throw new BusinessException("Please enter the 6-digit verification code.");
        }

        String cleanEmail = email.trim().toLowerCase();
        String cleanCode = code.trim();

        User user = userRepository.findByEmailIgnoreCase(cleanEmail)
                .orElseThrow(() -> new BusinessException("Invalid or expired verification code. Please check your email."));

        if (!user.isCustomer()) {
            throw new BusinessException("Password reset is only available for customer accounts.");
        }

        PasswordResetToken token = tokenRepository.findByUserAndToken(user, cleanCode)
                .orElseThrow(() -> new BusinessException("Invalid verification code. Please check your email or request a new code."));

        if (token.isExpired()) {
            tokenRepository.delete(token);
            throw new BusinessException("This verification code has expired. Please request a new code.");
        }

        // Code verified! Generate a single-use verified session token for the password reset screen
        String verifiedToken = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        token.setToken(verifiedToken);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(PasswordResetToken.EXPIRY_MINUTES));
        tokenRepository.save(token);

        log.info("Verification code validated for user {}", cleanEmail);
        return verifiedToken;
    }

    /**
     * Checks if a verified reset token exists and is still valid.
     */
    @Transactional(readOnly = true)
    public Optional<User> validateToken(String tokenString) {
        if (tokenString == null || tokenString.isBlank()) return Optional.empty();
        return tokenRepository.findByToken(tokenString.trim())
                .filter(t -> !t.isExpired())
                .map(PasswordResetToken::getUser);
    }

    /**
     * Updates the password using the verified reset token.
     */
    @Transactional
    public void resetPassword(String tokenString, String newPassword, String confirmPassword) {
        if (tokenString == null || tokenString.isBlank()) {
            throw new BusinessException("Session expired. Please request a new verification code.");
        }

        PasswordResetToken token = tokenRepository.findByToken(tokenString.trim())
                .orElseThrow(() -> new BusinessException("Your reset session is invalid or has expired. Please request a new code."));

        if (token.isExpired()) {
            tokenRepository.delete(token);
            throw new BusinessException("Your reset session has expired. Please request a new code.");
        }

        if (newPassword == null || newPassword.length() < 8) {
            throw new BusinessException("Password must be at least 8 characters long.");
        }

        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException("Passwords do not match.");
        }

        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Delete all reset tokens for this user
        tokenRepository.deleteByUser(user);

        // Terminate all existing sessions for safety
        userService.signOutEverywhere(user);
        try {
            notificationService.notify(
                user,
                com.medizon.domain.NotificationType.INFO,
                "Password changed",
                "Your MediZon account password was recently changed. If you did not make this change, please contact support immediately.",
                "/account"
            );
        } catch (Exception e) {
            log.warn("Could not dispatch password change notification for {}: {}", user.getEmail(), e.getMessage());
        }
        log.info("Password successfully updated for user {}", user.getEmail());
    }
}
