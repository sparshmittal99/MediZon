package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter @Setter @NoArgsConstructor
public class Notification {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Enumerated(EnumType.STRING)
    private NotificationType type;

    private String title;

    @Column(length = 1000)
    private String message;

    private String link;

    @Column(name = "is_read")
    private boolean read;

    /** No longer used: tabs now track what they've shown themselves. Kept because the column is NOT NULL. */
    private boolean pushed;

    private LocalDateTime createdAt = LocalDateTime.now();
}
