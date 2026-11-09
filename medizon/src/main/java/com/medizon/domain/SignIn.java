package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * An account signed in on one browser or phone (the mz_device cookie). Unlike the server's in-memory sessions, it
 * outlives a restart and covers devices kept signed in by a remember-me cookie (the Android app, "Keep me signed
 * in"), so "signed in on N devices" counts every phone and browser. Removed when that device signs out or is
 * signed out from elsewhere (SignInService).
 */
@Entity
@Table(name = "sign_ins", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_key"}))
@Getter @Setter @NoArgsConstructor
public class SignIn {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Column(name = "device_key", nullable = false, length = 40)
    private String deviceKey;

    /** "Chrome on Windows", "MediZon app on Android". */
    @Column(length = 60)
    private String label;

    /**
     * PHONE, TABLET or COMPUTER, from the browser's description (DeviceService.kind): the icon on the device list.
     * Not from the label, which for the app is just the phone's own name ("M2101K6P").
     */
    @Column(length = 10)
    private String kind;

    private LocalDateTime signedInAt = LocalDateTime.now();

    private LocalDateTime lastSeenAt = LocalDateTime.now();

    /** When this device's remember-me cookie runs out; null when it has none and lasts as long as the session. */
    private LocalDateTime rememberedUntil;

    /**
     * Set when this one device is signed out from another one. Its next request finds it and signs out there
     * (SignInGuard); a password sign-in on that device clears it.
     */
    private LocalDateTime signedOutAt;
}
