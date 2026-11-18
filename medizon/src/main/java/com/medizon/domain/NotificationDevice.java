package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One browser or Android app a user signs in on, recognised by a long-lived cookie (or the app's sync token). Its
 * member choice (everyone, or only the selected members) decides whose dose alarms ring and whose dose reminders it
 * shows. Every other notification of the account (orders, prescriptions, refills, a dose marked taken or skipped,
 * whoever it's about) goes to every device.
 */
@Entity
@Table(name = "notification_devices", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_key"}))
@Getter @Setter @NoArgsConstructor
public class NotificationDevice {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Column(name = "device_key", nullable = false, length = 40)
    private String deviceKey;

    /** "Chrome on Windows"; the user can rename it. */
    @Column(length = 60)
    private String label;

    private boolean allMembers = true;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "notification_device_members",
            joinColumns = @JoinColumn(name = "device_id"), inverseJoinColumns = @JoinColumn(name = "member_id"))
    private Set<Member> members = new HashSet<>();

    /** The user has chosen whose alerts this device gets; until then it gets everyone's and shows a prompt. */
    private boolean configured;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** SHA-256 (hex) of the Android app's sync token, which fetches dose alarms without a browser session. */
    @Column(length = 64, unique = true)
    private String appTokenHash;

    private LocalDateTime appLinkedAt;

    /** Firebase Cloud Messaging token of the Android app on this device: phone notifications (PushService). */
    @Column(length = 512)
    private String pushToken;
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    /** The Android app's version code, sent with each schedule fetch; null for versions from before it was sent. */
    private Integer appVersion;

    /** First app version that handles the dose-time backup push (DOSE_DUE); older ones would show it as a notification. */
    public static final int DOSE_BACKUP_VERSION = 3;

    /** Runs an app version that rings from the dose-time backup push (and ignores it when its own alarm rang). */
    public boolean handlesDoseBackup() { return appVersion != null && appVersion >= DOSE_BACKUP_VERSION; }

    /** Whether this device rings and shows dose reminders for {@code member} (its member choice). */
    public boolean allows(Member member) {
        return member == null || allMembers || getMemberIds().contains(member.getId());
    }

    /** Whether this device shows {@code n}: only dose reminders follow the member choice; everything else always shows. */
    public boolean shows(Notification n) {
        return n.getType() != NotificationType.DOSE_REMINDER || allows(n.getMember());
    }

    public Set<Long> getMemberIds() {
        return members.stream().map(Member::getId).collect(Collectors.toSet());
    }

    public String getSummary() {
        if (allMembers) return "All members";
        String names = members.stream().filter(m -> !m.isRemoved()).map(Member::getLabel).sorted()
                .collect(Collectors.joining(", "));
        return names.isEmpty() ? "No dose alarms" : names;
    }
}
