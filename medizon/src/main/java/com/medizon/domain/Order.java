package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity(name = "CustomerOrder")   // "Order" is a JPQL keyword
@Table(name = "orders")
@Getter @Setter @NoArgsConstructor
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.PLACED;

    /** The store that fulfils and invoices this order. */
    @ManyToOne
    private Store store;

    /**
     * Orders placed together share this: a cart with medicines from different stores becomes one order per store,
     * paid for once.
     */
    @Column(length = 20)
    private String checkoutRef;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod = PaymentMethod.COD;

    @Enumerated(EnumType.STRING)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    private OrderSource source = OrderSource.CUSTOMER;

    @Column(precision = 10, scale = 2) private BigDecimal subtotal = BigDecimal.ZERO;
    @Column(precision = 10, scale = 2) private BigDecimal deliveryFee = BigDecimal.ZERO;
    /** Paise added or taken off to bring the bill to whole rupees; included in {@link #total}. Zero on older orders. */
    @Column(precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 0")
    private BigDecimal roundOff = BigDecimal.ZERO;
    @Column(precision = 10, scale = 2) private BigDecimal total = BigDecimal.ZERO;

    @Column(length = 500)
    private String shippingAddress;
    private String contactPhone;

    /** Who receives the parcel; the account holder unless the address was for someone else. */
    @Column(length = 80)
    private String receiverName;

    /** "Home", "Office" ... of the saved address used. */
    @Column(length = 40)
    private String addressLabel;

    /** Billing details copied at checkout: the account's billing address, else the delivery address. */
    @Column(length = 80)
    private String billingName;

    @Column(length = 500)
    private String billingAddress;

    @Column(length = 15)
    private String billingGstin;

    /** State delivered to, snapshotted for the invoice's place of supply (CGST + SGST or IGST). */
    private String shippingState;

    /** Set when the order ships; GST invoice serials run consecutively per financial year. */
    @Column(unique = true, length = 16)
    private String invoiceNumber;
    private LocalDateTime invoicedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    private Prescription prescription;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
    private LocalDateTime deliveredAt;

    /** The receiver, falling back to the account holder for orders from before addresses had one. */
    public String getDeliverTo() {
        return receiverName != null && !receiverName.isBlank() ? receiverName : user.getFullName();
    }

    /** Who the invoice is billed to; orders from before billing addresses existed were billed to the account holder. */
    public String getBillTo() {
        return billingName != null && !billingName.isBlank() ? billingName : user.getFullName();
    }

    public String getBillToAddress() {
        return billingAddress != null && !billingAddress.isBlank() ? billingAddress : shippingAddress;
    }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    public boolean isCancellable() {
        return status == OrderStatus.PLACED || status == OrderStatus.CONFIRMED;
    }

    public boolean isAutoReorder() { return source == OrderSource.AUTO_REORDER; }

    /** Progress steps shown on the order page (cancelled orders don't show the tracker). */
    public List<OrderStatus> getTrackSteps() {
        return List.of(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    /** The tax invoice is issued when the order ships. */
    public boolean isInvoiceAvailable() {
        return status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED;
    }

    public int getItemCount() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }

    public boolean isRounded() { return roundOff != null && roundOff.signum() != 0; }

    public String getRoundOffLabel() { return RoundOff.label(roundOff); }
}
