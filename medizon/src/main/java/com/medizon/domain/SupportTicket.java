package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer's support request. It starts with the support team, who can pass it to a specialist team (pharmacy,
 * payments, the store that handled the order, or the admin); that team hands it back once solved, and support closes
 * it. The customer can reopen it for {@link #REOPEN_DAYS} days after it closes, and rate how it went.
 */
@Entity
@Table(name = "support_tickets", indexes = {
        @Index(name = "ix_support_tickets_user", columnList = "user_id"),
        @Index(name = "ix_support_tickets_status", columnList = "status")})
@Getter @Setter @NoArgsConstructor
public class SupportTicket {

    public static final int REOPEN_DAYS = 7;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    /** The order it's about, if any. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Order order;

    /** The store that handled that order: only its accounts see the ticket when it goes to the store team. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketTopic topic;

    @Column(nullable = false, length = 120)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private TicketStatus status = TicketStatus.OPEN;

    /** The specialist team it was last passed to; kept after they hand it back, so they can still look it up. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Role team;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<TicketMessage> messages = new ArrayList<>();

    private LocalDateTime createdAt = LocalDateTime.now();

    /** The last message or change, for sorting. */
    private LocalDateTime updatedAt = LocalDateTime.now();

    private LocalDateTime closedAt;

    @Column(nullable = false, columnDefinition = "integer not null default 0")
    private int reopenCount;

    /** 1 to 5, given after it closes; cleared if it's reopened. */
    private Integer rating;

    @Column(length = 500)
    private String ratingComment;

    /** "TKT-000042". */
    public String getNumber() {
        return id == null ? "New ticket" : "TKT-" + String.format("%06d", id);
    }

    public TicketMessage addMessage(TicketMessage m) {
        m.setTicket(this);
        messages.add(m);
        updatedAt = m.getCreatedAt();
        return m;
    }

    public List<TicketMessage> getCustomerMessages() {
        return messages.stream().filter(TicketMessage::isVisibleToCustomer).toList();
    }

    /** Newest message id the customer can see (0 for none), for pages that check for new replies. */
    public long getLatestCustomerMessageId() {
        return messages.stream().filter(TicketMessage::isVisibleToCustomer).mapToLong(TicketMessage::getId).max().orElse(0);
    }

    public long getLatestMessageId() {
        return messages.stream().mapToLong(TicketMessage::getId).max().orElse(0);
    }

    public boolean canReopen(LocalDateTime now) {
        return status.isClosed() && closedAt != null && closedAt.plusDays(REOPEN_DAYS).isAfter(now);
    }

    /** Last moment the customer can reopen it; null while it's open. */
    public LocalDateTime getReopenUntil() { return closedAt == null ? null : closedAt.plusDays(REOPEN_DAYS); }

    public boolean canRate() { return status.isClosed() && rating == null; }

    /** What the customer sees as the status: names the team it's with. */
    public String getCustomerStatus() {
        return status == TicketStatus.WITH_TEAM ? "With our " + teamLabel(team) : status.getCustomerLabel();
    }

    /** "Pharmacy team", "Support" ... as a team name. */
    public static String teamLabel(Role role) {
        if (role == null) return "MediZon team";
        return switch (role) {
            case SUPPORT -> "Support";
            case PHARMACIST -> "Pharmacy team";
            case FINANCE -> "Payments team";
            case STORE -> "Store team";
            case ADMIN -> "MediZon team";
            case CUSTOMER -> "Customer";
        };
    }

    public String getTeamLabel() { return teamLabel(team); }
}
