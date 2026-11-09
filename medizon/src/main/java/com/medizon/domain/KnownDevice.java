package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A browser or phone this account has signed in on before (its mz_device cookie). Unlike a sign-in it isn't removed
 * when the device signs out, so signing in again there sends no "new sign-in" email (NewDeviceAlertService).
 */
@Entity
@Table(name = "known_devices", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_key"}))
@Getter @Setter @NoArgsConstructor
public class KnownDevice {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Column(name = "device_key", nullable = false, length = 40)
    private String deviceKey;

    private LocalDateTime firstSeenAt = LocalDateTime.now();

    public KnownDevice(User user, String deviceKey) {
        this.user = user;
        this.deviceKey = deviceKey;
    }
}
