package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A person whose medicines an account manages: the account holder, a family member, or the "common medicines" shelf
 * shared by the household. Plans, cart lines, order lines and notifications are tagged with one.
 */
@Entity
@Table(name = "family_members")
@Getter @Setter @NoArgsConstructor
public class Member {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Column(nullable = false, length = 60)
    private String name;

    /** "Mother", "Son" ...; empty for the account holder. */
    @Column(length = 40)
    private String relation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MemberKind kind = MemberKind.FAMILY;

    /** Removed members keep their name on past orders but no longer show up anywhere else. */
    private boolean removed;

    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isSelf() { return kind == MemberKind.SELF; }

    public boolean isCommon() { return kind == MemberKind.COMMON; }

    /** Name shown in tabs, pickers and lists. */
    public String getLabel() {
        return switch (kind) {
            case SELF -> "Me";
            case COMMON -> "Common (OTC)";
            case FAMILY -> name;
        };
    }

    /** Appended to medicine names in alerts, e.g. "Metformin (Asha)"; empty for the account holder. */
    public String getSuffix() {
        return switch (kind) {
            case SELF -> "";
            case COMMON -> " (common)";
            case FAMILY -> " (" + name + ")";
        };
    }

    public String getInitial() {
        if (kind == MemberKind.COMMON) return "+";
        return name == null || name.isBlank() ? "?" : name.trim().substring(0, 1).toUpperCase();
    }
}
