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
 * One browser a user signs in on, recognised by a long-lived cookie, and whose alerts it pops up:
 * everyone's, or only the selected members'. Alerts that aren't about a member (orders, prescriptions) always show.
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
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    public boolean allows(Member member) {
        return member == null || allMembers || getMemberIds().contains(member.getId());
    }

    public Set<Long> getMemberIds() {
        return members.stream().map(Member::getId).collect(Collectors.toSet());
    }

    public String getSummary() {
        if (allMembers) return "All members";
        String names = members.stream().filter(m -> !m.isRemoved()).map(Member::getLabel).sorted()
                .collect(Collectors.joining(", "));
        return names.isEmpty() ? "Order and account alerts only" : names;
    }
}
