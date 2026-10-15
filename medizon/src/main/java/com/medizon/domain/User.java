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

    /** Where invoices are billed; empty means "bill to the delivery address". Set from the account menu. */
    @Embedded
    private BillingAddress billing;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** Admins can disable an account; a disabled user can't sign in and is signed out everywhere. */
    @Column(columnDefinition = "boolean not null default true")
    private boolean enabled = true;

    private LocalDateTime lastLoginAt;

    /** The store a STORE account works for. */
    @ManyToOne(fetch = FetchType.EAGER)
    private Store store;

    /**
     * Deleted accounts can't sign in and their personal data is gone; the row stays, renamed, so the orders and
     * invoices kept for the accounts still point at someone.
     */
    @Column(columnDefinition = "boolean not null default false")
    private boolean deleted;

    private LocalDateTime deletedAt;

    @Column(columnDefinition = "integer not null default 0")
    private int loginCount;

    public boolean isAdmin() { return role == Role.ADMIN; }

    /** Any back-office account: admin, pharmacist, support, finance or store. Staff can't shop. */
    public boolean isStaff() { return role.isStaff(); }

    public boolean isCustomer() { return role == Role.CUSTOMER; }

    /** A store account; it only sees its own store's catalogue, orders and returns. */
    public boolean isStoreStaff() { return role == Role.STORE; }

    public boolean is(Role r) { return role == r; }

    public boolean hasAddress() {
        return notBlank(addressLine) && notBlank(city) && notBlank(pincode);
    }

    public String getFormattedAddress() {
        return Stream.of(addressLine, city, state, pincode)
                .filter(User::notBlank)
                .collect(Collectors.joining(", "));
    }

    public boolean hasBillingAddress() { return billing != null && billing.isSet(); }

    public String getFirstName() {
        return fullName == null ? "" : fullName.trim().split("\\s+")[0];
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
