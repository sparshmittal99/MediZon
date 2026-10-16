package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** One entry in a ticket's conversation: a customer or staff message, an internal note, or an automatic update. */
@Entity
@Table(name = "ticket_messages", indexes = @Index(name = "ix_ticket_messages_ticket", columnList = "ticket_id"))
@Getter @Setter @NoArgsConstructor
public class TicketMessage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private SupportTicket ticket;

    /** Empty for automatic updates. */
    @ManyToOne(fetch = FetchType.LAZY)
    private User author;

    /** The author's role when they wrote it, so the label stays right if their role changes later. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Role authorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketMessageKind kind;

    @Column(nullable = false, length = 2000)
    private String body;

    @ElementCollection
    @CollectionTable(name = "ticket_attachments", joinColumns = @JoinColumn(name = "message_id"))
    @OrderColumn(name = "position")
    private List<TicketAttachment> attachments = new ArrayList<>();

    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isVisibleToCustomer() { return kind.isVisibleToCustomer(); }

    /** "Priya · Pharmacy team", as the customer sees a staff reply; the one admin account is just "MediZon team". */
    public String getStaffLabel() {
        if (author == null || authorRole == Role.ADMIN) return SupportTicket.teamLabel(authorRole);
        return author.getFirstName() + " · " + SupportTicket.teamLabel(authorRole);
    }
}
