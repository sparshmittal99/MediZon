package com.medizon.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Checks the Google ID token the Android app gets from the phone's Google account picker (Credential Manager): signed
 * by Google, for MediZon's client ID, in date, and carrying the nonce this browser was given. Google's public keys are
 * fetched once and cached.
 */
@Component
public class GoogleIdTokens {

    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final String KEYS = "https://www.googleapis.com/oauth2/v3/certs";

    private final String clientId;
    private volatile NimbusJwtDecoder decoder;

    public GoogleIdTokens(@Value("${medizon.google.client-id:}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public boolean enabled() { return !clientId.isEmpty(); }

    public String clientId() { return clientId; }

    /** Who Google says signed in. */
    public record Identity(String subject, String email, boolean emailVerified, String name) {}

    public Optional<Identity> verify(String idToken, String expectedNonce) {
        if (!enabled() || idToken == null || idToken.isBlank() || expectedNonce == null) return Optional.empty();
        try {
            Jwt jwt = decoder().decode(idToken.trim());
            if (!expectedNonce.equals(jwt.getClaimAsString("nonce"))) return Optional.empty();
            Object verified = jwt.getClaim("email_verified");
            boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(String.valueOf(verified));
            return Optional.of(new Identity(jwt.getSubject(), jwt.getClaimAsString("email"), emailVerified,
                    jwt.getClaimAsString("name")));
        } catch (JwtException e) {
            return Optional.empty();
        }
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder d = decoder;
        if (d == null) {
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder made = NimbusJwtDecoder.withJwkSetUri(KEYS).build();
                    made.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuerAndAudience()));
                    decoder = made;
                }
                d = decoder;
            }
        }
        return d;
    }

    private OAuth2TokenValidator<Jwt> issuerAndAudience() {
        OAuth2Error bad = new OAuth2Error("invalid_token", "Not a Google ID token for MediZon", null);
        return jwt -> {
            String iss = jwt.getClaimAsString("iss");
            boolean ok = iss != null && ISSUERS.contains(iss) && jwt.getAudience() != null && jwt.getAudience().contains(clientId);
            return ok ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(bad);
        };
    }
}
