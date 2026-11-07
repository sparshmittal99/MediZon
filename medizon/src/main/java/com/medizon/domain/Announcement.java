package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** A message the admin sent to every customer (sale, discount, update ...) by email, notification, or both. */
@Entity
@Table(name = "announcements")
@Getter @Setter @NoArgsConstructor
public class Announcement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnouncementKind kind;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(nullable = false, length = 500)
    private String message;

    /** Optional promo code shown with the message. */
    @Column(length = 20)
    private String promoCode;

    /** Optional page on MediZon the notification and email open, e.g. "/medicines". */
    @Column(length = 200)
    private String link;

    /** By email. */
    private boolean viaEmail;

    /** In the app's bell, as a browser notification and on the phone. */
    private boolean viaNotification;

    private int recipients;

    private String sentBy;

    private LocalDateTime sentAt = LocalDateTime.now();
}
