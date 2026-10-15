package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One shopping trip of an account: opened when the first medicine goes in, closed at checkout. It stays the same
 * across sign-ins and devices; the orders placed from it point back to it. A signed-out visitor's cart lives in the
 * session and becomes the account's cart when they sign in.
 */
@Entity
@Table(name = "carts", indexes = @Index(columnList = "user_id, status"))
@Getter @Setter @NoArgsConstructor
public class Cart {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CartStatus status = CartStatus.ACTIVE;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime checkedOutAt;

    public Cart(User user) { this.user = user; }

    public boolean isActive() { return status == CartStatus.ACTIVE; }
}
