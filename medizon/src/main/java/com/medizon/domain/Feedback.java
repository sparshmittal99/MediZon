package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Free-text feedback a customer sends through the assistant. Read by admins next to order reviews. */
@Entity
@Getter @Setter @NoArgsConstructor
public class Feedback {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Column(nullable = false, length = 1000)
    private String message;

    /** Topic picked by the assistant, e.g. "Delivery speed" or "Pharmacist question". */
    private String category;

    /** Optional 1-5 stars when the customer mentioned one. */
    private Integer rating;

    private LocalDateTime createdAt = LocalDateTime.now();
}
