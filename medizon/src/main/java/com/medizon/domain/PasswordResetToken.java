package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A one-time token emailed to a customer who clicked "Forgot password?".
 * Expires after 30 minutes and is deleted once used.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter @Setter @NoArgsConstructor
public class PasswordResetToken {

    public static final int EXPIRY_MINUTES = 15;

    /** How long a customer waits before another code can be emailed. */
    public static final int RESEND_COOLDOWN_SECONDS = 30;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String token;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private User user;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public PasswordResetToken(String token, User user) {
        this.token = token;
        this.user = user;
        this.expiresAt = LocalDateTime.now().plusMinutes(EXPIRY_MINUTES);
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}

