package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** A customer asking to send back part or all of a delivered order. An admin approves it before pickup and refund. */
@Entity
@Table(name = "return_requests")
@Getter @Setter @NoArgsConstructor
public class ReturnRequest {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String returnNumber;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Order order;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ReturnItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private ReturnStatus status = ReturnStatus.REQUESTED;

    @Enumerated(EnumType.STRING)
    private ReturnReason reason;

    @Column(length = 1000)
    private String details;

    @ElementCollection
    @CollectionTable(name = "return_images", joinColumns = @JoinColumn(name = "return_id"))
    @OrderColumn(name = "position")
    private List<ReturnImage> images = new ArrayList<>();

    /** Why it was approved or rejected, shown to the customer. */
    @Column(length = 1000)
    private String adminNote;

    @Column(precision = 10, scale = 2)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    /** The returned packs went back on the shelf when picked up. */
    private boolean restocked;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime decidedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime refundedAt;

    public void addItem(ReturnItem item) {
        item.setRequest(this);
        items.add(item);
    }

    public boolean isOpen() { return status.isOpen(); }

    public int getPackCount() { return items.stream().mapToInt(ReturnItem::getQuantity).sum(); }

    /** Progress steps shown on the return (rejected returns don't show the tracker). */
    public List<ReturnStatus> getTrackSteps() {
        return List.of(ReturnStatus.REQUESTED, ReturnStatus.APPROVED, ReturnStatus.PICKED_UP, ReturnStatus.REFUNDED);
    }

    public boolean reached(ReturnStatus step) {
        return getTrackSteps().indexOf(status) >= getTrackSteps().indexOf(step);
    }
}
