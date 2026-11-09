package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The "This wasn't me" link in a new sign-in email: signs the account out everywhere and asks for a new password.
 * Only the hash of the link's token is kept. Used once; runs out after {@value #DAYS} days.
 */
@Entity
@Table(name = "secure_account_tokens")
@Getter @Setter @NoArgsConstructor
public class SecureAccountToken {

    public static final int DAYS = 7;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    private User user;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** What the email said signed in ("Chrome on Windows"), shown again on the page the link opens. */
    @Column(length = 60)
    private String deviceLabel;

    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime expiresAt = LocalDateTime.now().plusDays(DAYS);

    public boolean isExpired() { return LocalDateTime.now().isAfter(expiresAt); }
}
