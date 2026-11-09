package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A browser or phone where the user ticked "Don't ask again on this device" after entering a 2FA code. Kept in the
 * database, so it survives restarts and redeploys. It lasts until 2FA is turned off, the password changes, the
 * account signs out its other devices, or this device is signed out from See devices (SignInService).
 * <p>
 * The browser proves it's this device with two cookies: mz_device (which device) and mz_trust (a secret whose hash is
 * kept here).
 */
@Entity
@Table(name = "trusted_devices", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_key"}))
@Getter @Setter @NoArgsConstructor
public class TrustedDevice {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Column(name = "device_key", nullable = false, length = 40)
    private String deviceKey;

    /** SHA-256 of the browser's mz_trust cookie. */
    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(length = 60)
    private String label;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime lastUsedAt;
}
