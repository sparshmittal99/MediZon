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

    /** The cart it was checked out from; empty for automatic refills and orders from before carts had ids. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Cart cart;

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

    /** Promo applied at checkout and the rupees it took off the medicines (already out of {@link #total}). */
    @Column(length = 20)
    private String couponCode;
    @Column(precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 0")
    private BigDecimal discount = BigDecimal.ZERO;

    /** Part of the total paid with MediZon Money; the rest was paid by {@link #paymentMethod}. */
    @Column(precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 0")
    private BigDecimal walletUsed = BigDecimal.ZERO;
    /** MediZon Money already given back on returns or cancellation, so it isn't given back twice. */
    @Column(precision = 10, scale = 2, columnDefinition = "numeric(10,2) not null default 0")
    private BigDecimal walletRefunded = BigDecimal.ZERO;

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

    /** Every prescription its lines used (the order's own one for orders from before lines recorded theirs). */
    public List<Prescription> getPrescriptionsUsed() {
        List<Prescription> out = new ArrayList<>();
        items.forEach(i -> i.getPrescriptionUses().forEach(u -> {
            if (!out.contains(u.getPrescription())) out.add(u.getPrescription());
        }));
        if (out.isEmpty() && prescription != null) out.add(prescription);
        return out;
    }

    /** The prescribing doctor and the prescription's date when ordered, printed on the invoice. */
    @Column(length = 255)
    private String doctorName;
    private LocalDate prescriptionDate;

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

    public boolean isDiscounted() { return discount != null && discount.signum() > 0; }

    public boolean isPaidWithWallet() { return walletUsed != null && walletUsed.signum() > 0; }

    /** What was paid (or is due in cash) other than MediZon Money. */
    public BigDecimal getAmountDue() {
        return total.subtract(walletUsed == null ? BigDecimal.ZERO : walletUsed);
    }

    /** Medicines before the promo. */
    public BigDecimal getItemsTotal() { return subtotal; }

    /** "MediZon Money ₹120.00 + UPI ₹80.00", "MediZon Money" or "UPI". */
    public String getPaymentLabel() {
        if (!isPaidWithWallet() || paymentMethod == PaymentMethod.WALLET) return paymentMethod.getLabel();
        return PaymentMethod.WALLET.getLabel() + " ₹" + walletUsed + " + " + paymentMethod.getLabel() + " ₹" + getAmountDue();
    }

    public String getRoundOffLabel() { return RoundOff.label(roundOff); }
}
