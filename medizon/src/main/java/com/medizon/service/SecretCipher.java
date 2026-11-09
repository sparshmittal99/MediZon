package com.medizon.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts authenticator-app secrets before they're saved (AES-GCM, key from TWO_FACTOR_KEY), so a copy of the
 * database alone can't make anyone's codes. Without the key they're kept as they are ("p:" in front); those still
 * work once a key is set, and are encrypted the next time 2FA is turned on.
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String ENCRYPTED = "v1:";
    private static final String PLAIN = "p:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(@Value("${medizon.security.two-factor-key:}") String configured) {
        if (configured == null || configured.isBlank()) {
            log.warn("TWO_FACTOR_KEY isn't set: authenticator secrets are stored unencrypted");
            this.key = null;
        } else {
            try {
                byte[] k = MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8));
                this.key = new SecretKeySpec(k, "AES");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public String encrypt(String plain) {
        if (key == null) return PLAIN + plain;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ENCRYPTED + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The secret, or null when it can't be read (encrypted with a key that's gone or changed). */
    public String decrypt(String stored) {
        if (stored == null) return null;
        if (stored.startsWith(PLAIN)) return stored.substring(PLAIN.length());
        if (!stored.startsWith(ENCRYPTED) || key == null) {
            log.error("A 2FA secret can't be read: TWO_FACTOR_KEY is missing");
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(ENCRYPTED.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.error("A 2FA secret can't be read: TWO_FACTOR_KEY has changed");
            return null;
        }
    }
}
