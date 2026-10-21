package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /** Where the packs are collected from, copied when the return is requested. Null on returns from before pickups were booked. */
    @Column(length = 80)
    private String pickupName;

    @Column(length = 15)
    private String pickupPhone;

    @Column(length = 500)
    private String pickupAddress;

    private LocalDate pickupDate;

    /** Null on returns from before the customer chose, which were refunded to the payment method used. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private RefundMode refundMode;

    @Column(length = 60)
    private String refundUpiId;

    @Column(length = 80)
    private String refundAccountName;

    @Column(length = 20)
    private String refundAccountNumber;

    @Column(length = 11)
    private String refundIfsc;

    /** Part of the refund paid into MediZon Money at pickup; the rest goes to {@link #refundMode} on {@link #refundDueOn}. */
    @Column(precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 0")
    private BigDecimal walletRefund = BigDecimal.ZERO;

    /** When the rest of the refund lands, set at pickup. */
    private LocalDate refundDueOn;

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

    /** "your UPI", "UPI ID name@bank" or "bank account ••••6789 (HDFC0001234)"; null for a cash order from before refunds had a mode. */
    public String getRefundTo() {
        RefundMode mode = refundMode != null ? refundMode
                : order.getPaymentMethod() == PaymentMethod.COD ? null : RefundMode.ORIGINAL;
        if (mode == null) return null;
        return switch (mode) {
            case ORIGINAL -> "your " + order.getPaymentMethod().getLabel();
            case WALLET -> "your MediZon Money";
            case UPI -> "UPI ID " + refundUpiId;
            case BANK -> "bank account " + getMaskedAccountNumber() + " (" + refundIfsc + ")";
        };
    }

    /** The refund less what went into MediZon Money: what is paid out to {@link #getRefundTo()}. */
    public BigDecimal getPayoutAmount() {
        return refundAmount.subtract(walletRefund == null ? BigDecimal.ZERO : walletRefund);
    }

    /** "Instantly after pickup", "2 days after pickup"; null for returns from before refunds had a mode. */
    public String getRefundTiming() {
        return refundMode == null ? null : refundMode.timing(order.getPaymentMethod());
    }

    /** Last four digits only, for the customer's screens and notifications. */
    public String getMaskedAccountNumber() {
        if (refundAccountNumber == null) return null;
        int n = refundAccountNumber.length();
        return "••••" + refundAccountNumber.substring(Math.max(0, n - 4));
    }

    /** Progress steps shown on the return (rejected returns don't show the tracker). */
    public List<ReturnStatus> getTrackSteps() {
        return List.of(ReturnStatus.REQUESTED, ReturnStatus.APPROVED, ReturnStatus.PICKED_UP, ReturnStatus.REFUNDED);
    }

    public boolean reached(ReturnStatus step) {
        return getTrackSteps().indexOf(status) >= getTrackSteps().indexOf(step);
    }
}
