package com.medizon.service;

import com.medizon.domain.User;
import com.medizon.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Authenticator-app codes (RFC 6238) and turning two-step verification on and off. */
class TwoFactorTest {

    /** RFC 6238's SHA-1 test key, "12345678901234567890". */
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void codesMatchTheStandardsTestValues() {
        // RFC 6238 appendix B gives 8 digits; authenticator apps show the last 6
        assertThat(Totp.code(RFC_SECRET, Totp.step(59))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(1111111109))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(1234567890))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.step(2000000000))).isEqualTo("279037");
    }

    @Test
    void base32RoundTripsAndNewSecretsAreAppSized() {
        byte[] raw = "any bytes at all!".getBytes(StandardCharsets.UTF_8);
        assertThat(Totp.unbase32(Totp.base32(raw))).isEqualTo(raw);
        assertThat(Totp.newSecret()).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.grouped("ABCDEFGHIJKL")).isEqualTo("ABCD EFGH IJKL");
    }

    @Test
    void aSlightlyWrongPhoneClockStillWorksButNotMore() {
        long now = Totp.step(1_700_000_000L);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now - 1), now, 0)).isEqualTo(now - 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now + 1), now, 0)).isEqualTo(now + 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now - 3), now, 0)).isEqualTo(-1);
        assertThat(Totp.match(RFC_SECRET, "12345", now, 0)).isEqualTo(-1);
        assertThat(Totp.match(RFC_SECRET, "abcdef", now, 0)).isEqualTo(-1);
    }

    @Test
    void aCodeWorksOnlyOnce() {
        long now = Totp.step(1_700_000_000L);
        String code = Totp.code(RFC_SECRET, now);
        assertThat(Totp.match(RFC_SECRET, code, now, now - 1)).isEqualTo(now);
        assertThat(Totp.match(RFC_SECRET, code, now, now)).isEqualTo(-1);   // already used
    }

    @Test
    void theQrCodeHoldsAnOtpauthLink() {
        String uri = Totp.uri("MediZon", "asha@example.com", "ABCDEFGH");
        assertThat(uri).startsWith("otpauth://totp/MediZon:asha%40example.com?secret=ABCDEFGH&issuer=MediZon");
        assertThat(QrCodes.svg(uri)).startsWith("<svg").contains("<path");
    }

    @Test
    void secretsAreEncryptedWhenAKeyIsSet() {
        SecretCipher keyed = new SecretCipher("a-long-random-key");
        String stored = keyed.encrypt("JBSWY3DPEHPK3PXP");
        assertThat(stored).startsWith("v1:").doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(keyed.decrypt(stored)).isEqualTo("JBSWY3DPEHPK3PXP");
        // A changed key can't read it (rather than producing wrong codes)
        assertThat(new SecretCipher("another-key").decrypt(stored)).isNull();
        // Without a key: kept as is, and still readable once a key is set
        String plain = new SecretCipher("").encrypt("JBSWY3DPEHPK3PXP");
        assertThat(keyed.decrypt(plain)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    // ------------------------------------------------------------------ TwoFactorService

    private UserRepository users;
    private TrustedDeviceService trusted;
    private TwoFactorService service;
    private User user;
    private final Instant now = Instant.ofEpochSecond(1_700_000_000L);

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        trusted = mock(TrustedDeviceService.class);
        service = new TwoFactorService(users, new SecretCipher("test-key"), trusted, mock(NotificationService.class));
        service.setClock(Clock.fixed(now, ZoneOffset.UTC));
        user = new User();
        user.setId(5L);
        user.setEmail("asha@example.com");
        when(users.findById(5L)).thenReturn(Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    private String codeNow(String secret) { return Totp.code(secret, Totp.step(now.getEpochSecond())); }

    @Test
    void turningOnNeedsTheRightCodeAndGivesEightBackupCodes() {
        String secret = Totp.newSecret();
        assertThrows(BusinessException.class, () -> service.enable(user, secret, "000000".equals(codeNow(secret)) ? "111111" : "000000"));
        assertThat(user.isTwoFactorEnabled()).isFalse();

        List<String> codes = service.enable(user, secret, codeNow(secret));
        assertThat(user.isTwoFactorEnabled()).isTrue();
        assertThat(codes).hasSize(8).allMatch(c -> c.matches("[a-z2-9]{4}-[a-z2-9]{4}"));
        assertThat(user.getBackupCodesLeft()).isEqualTo(8);
        assertThat(user.getTwoFactorSecret()).startsWith("v1:");
        verify(trusted).forgetAll(user);
    }

    @Test
    void signInTakesTheAppCodeOnceOrABackupCodeOnce() {
        String secret = Totp.newSecret();
        List<String> codes = service.enable(user, secret, codeNow(secret));
        // The code used to turn it on can't sign in as well
        assertThat(service.check(user, codeNow(secret))).isEqualTo(TwoFactorService.Check.WRONG);

        String backup = codes.get(3);
        assertThat(service.check(user, backup.toUpperCase().replace("-", " "))).isEqualTo(TwoFactorService.Check.BACKUP_CODE);
        assertThat(user.getBackupCodesLeft()).isEqualTo(7);
        assertThat(service.check(user, backup)).isEqualTo(TwoFactorService.Check.WRONG);
        assertThat(service.check(user, "zzzz-zzzz")).isEqualTo(TwoFactorService.Check.WRONG);
    }

    @Test
    void turningOffNeedsACodeAndForgetsTrustedDevices() {
        String secret = Totp.newSecret();
        List<String> codes = service.enable(user, secret, codeNow(secret));
        assertThrows(BusinessException.class, () -> service.disable(user, "nope"));
        assertThat(user.isTwoFactorEnabled()).isTrue();

        service.disable(user, codes.get(0));
        assertThat(user.isTwoFactorEnabled()).isFalse();
        assertThat(user.getTwoFactorSecret()).isNull();
        assertThat(user.getBackupCodesLeft()).isZero();
        verify(trusted, times(2)).forgetAll(user);
    }

    @Test
    void onlyTheAdminCanResetSomeoneElses() {
        String secret = Totp.newSecret();
        service.enable(user, secret, codeNow(secret));
        User support = new User();
        support.setRole(com.medizon.domain.Role.SUPPORT);
        assertThrows(BusinessException.class, () -> service.resetByAdmin(5L, support));

        User admin = new User();
        admin.setRole(com.medizon.domain.Role.ADMIN);
        admin.setEmail("admin@medizon.in");
        service.resetByAdmin(5L, admin);
        assertThat(user.isTwoFactorEnabled()).isFalse();
    }
}
