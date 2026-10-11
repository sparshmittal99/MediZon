package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.CUSTOMER;

    private String addressLine;
    private String city;
    private String state;
    private String pincode;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** Admins can disable an account; a disabled user can't sign in and is signed out everywhere. */
    @Column(columnDefinition = "boolean not null default true")
    private boolean enabled = true;

    private LocalDateTime lastLoginAt;

    @Column(columnDefinition = "integer not null default 0")
    private int loginCount;

    public boolean isAdmin() { return role == Role.ADMIN; }

    public boolean hasAddress() {
        return notBlank(addressLine) && notBlank(city) && notBlank(pincode);
    }

    public String getFormattedAddress() {
        return Stream.of(addressLine, city, state, pincode)
                .filter(User::notBlank)
                .collect(Collectors.joining(", "));
    }

    public String getFirstName() {
        return fullName == null ? "" : fullName.trim().split("\\s+")[0];
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
