package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** A one-off reminder ("call the supplier tomorrow at 11") set through the assistant, for customers and admins. */
@Entity
@Getter @Setter @NoArgsConstructor
public class Reminder {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Column(nullable = false, length = 300)
    private String text;

    @Column(nullable = false)
    private LocalDateTime dueAt;

    /** The notification has gone out. */
    private boolean sent;

    /** The user marked it done. */
    private boolean done;

    private LocalDateTime createdAt = LocalDateTime.now();
}
