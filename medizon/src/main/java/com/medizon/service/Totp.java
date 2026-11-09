package com.medizon.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The 6-digit codes authenticator apps show (TOTP, RFC 6238: HMAC-SHA1, 30-second steps), as Google Authenticator,
 * Microsoft Authenticator, Authy and the rest make them. Nothing here talks to any service.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int STEP_SECONDS = 30;
    /** A phone whose clock is up to this many steps (30 s each) off still works. */
    static final int DRIFT_STEPS = 1;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** A new secret: 20 random bytes, in Base32 as the apps take it. */
    public static String newSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return base32(b);
    }

    /** The 30-second step a moment falls in. */
    public static long step(long epochSeconds) { return Math.floorDiv(epochSeconds, STEP_SECONDS); }

    /** The code for one step. */
    public static String code(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(unbase32(base32Secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", bin % (int) Math.pow(10, DIGITS));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The step {@code code} is right for, around {@code nowStep}; -1 when it's wrong. A step at or before
     * {@code lastUsedStep} doesn't count, so a code that has been used can't be used again.
     */
    public static long match(String base32Secret, String code, long nowStep, long lastUsedStep) {
        if (code == null) return -1;
        String c = code.replaceAll("\\s", "");
        if (!c.matches("\\d{" + DIGITS + "}")) return -1;
        long found = -1;
        for (long s = nowStep - DRIFT_STEPS; s <= nowStep + DRIFT_STEPS; s++) {
            // Check every step, so how long this takes says nothing about which one matched
            if (constantEquals(code(base32Secret, s), c) && s > lastUsedStep && found < 0) found = s;
        }
        return found;
    }

    /** What the QR code holds: otpauth://totp/MediZon:me@example.com?secret=...&issuer=MediZon */
    public static String uri(String issuer, String account, String base32Secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** "ABCD EFGH IJKL ..." for typing the key in by hand. */
    public static String grouped(String base32Secret) {
        return base32Secret.replaceAll("(.{4})(?!$)", "$1 ");
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        String clean = s.replaceAll("[\\s=-]", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char ch : clean.toCharArray()) {
            int v = BASE32.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("Not Base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }

    private static boolean constantEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
