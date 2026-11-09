package com.medizon.service;

import com.medizon.domain.NotificationType;
import com.medizon.domain.User;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Two-step sign-in with an authenticator app: turning it on (scan a QR code, confirm with a code), checking codes at
 * sign-in, backup codes for a lost phone, and turning it off. The admin can turn it off for an account that has lost
 * both its phone and its backup codes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwoFactorService {

    public static final String ISSUER = "MediZon";
    public static final int BACKUP_CODES = 8;
    private static final String BACKUP_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final SecretCipher cipher;
    private final TrustedDeviceService trustedDevices;
    private final NotificationService notifications;
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) { this.clock = clock; }

    /** What the setup page shows for a new secret: the QR code and the key to type in instead. */
    public record Setup(String secret, String qrSvg, String groupedKey) {}

    public Setup setup(User user, String secret) {
        return new Setup(secret, QrCodes.svg(Totp.uri(ISSUER, user.getEmail(), secret)), Totp.grouped(secret));
    }

    /**
     * Turns 2FA on once the app shows the right code for {@code secret}. Returns the backup codes, shown to the user
     * this once (only their hashes are kept).
     */
    @Transactional
    public List<String> enable(User user, String secret, String code) {
        if (user.isTwoFactorEnabled()) throw new BusinessException("Two-step verification is already on.");
        if (secret == null) throw new BusinessException("The setup ran out. Start again.");
        long step = Totp.match(secret, code, nowStep(), 0);
        if (step < 0) throw new BusinessException("That code isn't right. Check the time on your phone is set automatically, then enter the code shown now.");
        User u = fresh(user);
        u.setTwoFactorSecret(cipher.encrypt(secret));
        u.setTwoFactorLastStep(step);
        u.setTwoFactorEnabledAt(LocalDateTime.now());
        List<String> codes = newBackupCodes();
        u.setTwoFactorBackupCodes(hashes(codes));
        users.save(u);
        trustedDevices.forgetAll(u);
        copy(u, user);
        tell(u, "Two-step verification is on",
                "Signing in to MediZon now takes a code from your authenticator app as well as your password or Google account.");
        return codes;
    }

    /** Turns 2FA off after a code from the app (or a backup code). Every trusted device is forgotten. */
    @Transactional
    public void disable(User user, String code) {
        User u = fresh(user);
        if (!u.isTwoFactorEnabled()) throw new BusinessException("Two-step verification is already off.");
        if (check(u, code) == Check.WRONG) throw new BusinessException("That code isn't right. Enter the code your authenticator app shows now.");
        turnOff(u);
        copy(u, user);
        tell(u, "Two-step verification is off",
                "Signing in to MediZon no longer asks for a code from your authenticator app. If you didn't do this, change your password now.");
    }

    /** New backup codes after a code from the app; the old ones stop working. */
    @Transactional
    public List<String> newBackupCodes(User user, String code) {
        User u = fresh(user);
        if (!u.isTwoFactorEnabled()) throw new BusinessException("Turn on two-step verification first.");
        if (check(u, code) == Check.WRONG) throw new BusinessException("That code isn't right. Enter the code your authenticator app shows now.");
        List<String> codes = newBackupCodes();
        u.setTwoFactorBackupCodes(hashes(codes));
        users.save(u);
        copy(u, user);
        return codes;
    }

    /** The admin turns 2FA off for an account that lost its phone and backup codes. */
    @Transactional
    public User resetByAdmin(Long id, User actingAdmin) {
        if (!actingAdmin.isAdmin()) throw new BusinessException("Only the admin can reset two-step verification.");
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User not found."));
        if (u.isDeleted()) throw new BusinessException("That account has been deleted.");
        if (!u.isTwoFactorEnabled()) throw new BusinessException(u.getFullName() + " doesn't use two-step verification.");
        turnOff(u);
        tell(u, "Two-step verification was reset",
                "MediZon support turned off two-step verification on your account. You can turn it on again in Account settings.");
        log.info("Admin {} reset 2FA for account {}", actingAdmin.getEmail(), u.getId());
        return u;
    }

    public enum Check { CODE, BACKUP_CODE, WRONG }

    /**
     * Checks a sign-in code: the app's 6-digit code (each used once), or one of the backup codes (which is then used
     * up). Saves what it used.
     */
    @Transactional
    public Check check(User user, String code) {
        User u = fresh(user);
        if (!u.isTwoFactorEnabled() || code == null || code.isBlank()) return Check.WRONG;
        String c = code.trim();
        if (c.replaceAll("\\s", "").matches("\\d{" + Totp.DIGITS + "}")) {
            String secret = cipher.decrypt(u.getTwoFactorSecret());
            if (secret == null) return Check.WRONG;
            long step = Totp.match(secret, c, nowStep(), u.getTwoFactorLastStep());
            if (step < 0) return Check.WRONG;
            u.setTwoFactorLastStep(step);
            users.save(u);
            return Check.CODE;
        }
        String hash = Tokens.sha256(normalizeBackup(c));
        List<String> left = new ArrayList<>(backupHashes(u));
        if (!left.removeIf(h -> Tokens.sameHash(h, hash))) return Check.WRONG;
        u.setTwoFactorBackupCodes(String.join(",", left));
        users.save(u);
        if (left.isEmpty()) {
            tell(u, "You've used your last backup code",
                    "Make new backup codes in Account settings > Two-step verification, in case you lose your phone.");
        }
        return Check.BACKUP_CODE;
    }

    private void turnOff(User u) {
        u.setTwoFactorSecret(null);
        u.setTwoFactorEnabledAt(null);
        u.setTwoFactorBackupCodes(null);
        u.setTwoFactorLastStep(0);
        users.save(u);
        trustedDevices.forgetAll(u);
    }

    private long nowStep() { return Totp.step(clock.instant().getEpochSecond()); }

    private User fresh(User user) {
        return users.findById(user.getId()).orElseThrow(() -> new NotFoundException("User not found."));
    }

    /** Keeps the caller's copy (the signed-in user on this request) in step with what was saved. */
    private static void copy(User from, User to) {
        if (from == to) return;
        to.setTwoFactorSecret(from.getTwoFactorSecret());
        to.setTwoFactorEnabledAt(from.getTwoFactorEnabledAt());
        to.setTwoFactorBackupCodes(from.getTwoFactorBackupCodes());
        to.setTwoFactorLastStep(from.getTwoFactorLastStep());
    }

    private void tell(User u, String title, String message) {
        try {
            notifications.notify(u, NotificationType.INFO, title, message, "/account#two-factor");
        } catch (Exception e) {
            log.warn("Could not notify account {} about 2FA: {}", u.getId(), e.getMessage());
        }
    }

    /** "k7mq-3hfp": 8 letters and digits that can't be mistaken for each other. */
    static List<String> newBackupCodes() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < BACKUP_CODES; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 8; j++) {
                if (j == 4) sb.append('-');
                sb.append(BACKUP_ALPHABET.charAt(RANDOM.nextInt(BACKUP_ALPHABET.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    static String normalizeBackup(String code) {
        return code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
    }

    private static String hashes(List<String> codes) {
        return codes.stream().map(c -> Tokens.sha256(normalizeBackup(c))).collect(Collectors.joining(","));
    }

    private static List<String> backupHashes(User u) {
        String s = u.getTwoFactorBackupCodes();
        return s == null || s.isBlank() ? List.of() : Arrays.asList(s.split(","));
    }
}
