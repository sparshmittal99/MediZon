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

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod = PaymentMethod.COD;

    @Enumerated(EnumType.STRING)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    private OrderSource source = OrderSource.CUSTOMER;

    @Column(precision = 10, scale = 2) private BigDecimal subtotal = BigDecimal.ZERO;
    @Column(precision = 10, scale = 2) private BigDecimal deliveryFee = BigDecimal.ZERO;
    @Column(precision = 10, scale = 2) private BigDecimal total = BigDecimal.ZERO;

    @Column(length = 500)
    private String shippingAddress;
    private String contactPhone;

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
}
