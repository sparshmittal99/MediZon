package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Scheduled downtime the admin announced: customers are told by notification, email, a banner and the Android app. */
@Entity
@Table(name = "maintenance_windows")
@Getter @Setter @NoArgsConstructor
public class MaintenanceWindow {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime startsAt;

    @Column(nullable = false)
    private LocalDateTime endsAt;

    private boolean cancelled;

    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isOver(LocalDateTime now) { return !endsAt.isAfter(now); }

    public boolean isUnderway(LocalDateTime now) { return !startsAt.isAfter(now) && endsAt.isAfter(now); }
}
