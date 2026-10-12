package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.CartSummary;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.OrderRepository;
import com.medizon.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orders;
    private final MedicineRepository medicines;
    private final PrescriptionRepository prescriptions;
    private final CartService cartService;
    private final NotificationService notifications;
    private final MedicationPlanService planService;
    private final InvoiceService invoiceService;

    /** One order line: packs of a medicine and who they're for. */
    private record Line(Long medicineId, int quantity, Set<Member> members) {}

    // ------------------------------------------------------------------ customer checkout

    @Transactional
    public Order checkout(User user, PaymentMethod paymentMethod, Long prescriptionId) {
        if (user.isAdmin()) throw new BusinessException("Admin accounts can't place orders.");
        CartSummary cart = cartService.summary(user);
        if (cart.isEmpty()) throw new BusinessException("Your cart is empty.");

        Prescription rx = null;
        if (cart.isPrescriptionRequired()) {
            if (prescriptionId == null) {
                throw new BusinessException("Your cart has prescription medicines. Choose an approved prescription.");
            }
            rx = prescriptions.findByIdAndUser(prescriptionId, user)
                    .filter(Prescription::isValid)
                    .orElseThrow(() -> new BusinessException("Choose an approved prescription that hasn't expired."));
        }

        List<Line> lines = new ArrayList<>();
        for (CartItem i : cart.getItems()) {
            if (i.getMembers().isEmpty()) throw new BusinessException("Choose who " + i.getMedicine().getName() + " is for.");
            lines.add(new Line(i.getMedicine().getId(), i.getQuantity(), new HashSet<>(i.getMembers())));
        }

        Order order = createOrder(user, lines, paymentMethod, rx, OrderSource.CUSTOMER);
        cartService.clear(user);
        notifications.notify(user, NotificationType.ORDER_UPDATE, "Order placed",
                "Order " + order.getOrderNumber() + " for ₹" + order.getTotal() + " is placed.",
                "/orders/" + order.getId());
        return order;
    }

    // ------------------------------------------------------------------ auto refill

    /** Why an auto-refill can't be placed right now, or empty if it can. Never throws. */
    public Optional<String> autoReorderBlocker(MedicationPlan plan) {
        User user = plan.getUser();
        Medicine m = plan.getMedicine();
        if (!user.hasAddress()) return Optional.of("there's no delivery address on your profile");
        if (!m.isActive()) return Optional.of(m.getName() + " is no longer sold on MediZon");
        if (m.getStock() < plan.getReorderPacks()) return Optional.of(m.getName() + " is out of stock");
        if (m.isExpired()) return Optional.of("the stock of " + m.getName() + " has expired");
        if (m.isPrescriptionRequired() && findValidPrescription(user).isEmpty()) {
            return Optional.of("there's no approved prescription on file");
        }
        return Optional.empty();
    }

    @Transactional
    public Order placeAutoReorder(MedicationPlan plan) {
        User user = plan.getUser();
        Medicine m = plan.getMedicine();
        Prescription rx = m.isPrescriptionRequired() ? findValidPrescription(user).orElse(null) : null;
        Set<Member> forWhom = plan.getMember() == null ? Set.of() : Set.of(plan.getMember());
        return createOrder(user, List.of(new Line(m.getId(), Math.max(1, plan.getReorderPacks()), forWhom)),
                PaymentMethod.COD, rx, OrderSource.AUTO_REORDER);
    }

    private Optional<Prescription> findValidPrescription(User user) {
        return prescriptions.findByUserAndStatusOrderByReviewedAtDesc(user, PrescriptionStatus.APPROVED)
                .stream().filter(Prescription::isValid).findFirst();
    }

    // ------------------------------------------------------------------ shared core

    private Order createOrder(User user, List<Line> lines, PaymentMethod paymentMethod,
                              Prescription rx, OrderSource source) {
        if (!user.hasAddress()) throw new BusinessException("Add a delivery address to your profile first.");

        Order order = new Order();
        order.setOrderNumber(newOrderNumber());
        order.setUser(user);
        order.setPaymentMethod(paymentMethod);
        order.setSource(source);
        order.setPrescription(rx);
        order.setShippingAddress(user.getFormattedAddress());
        order.setContactPhone(user.getPhone());
        order.setShippingState(user.getState());

        BigDecimal subtotal = BigDecimal.ZERO;
        for (Line line : lines) {
            Medicine m = medicines.lockById(line.medicineId())
                    .orElseThrow(() -> new BusinessException("A medicine in your order no longer exists."));
            int qty = line.quantity();
            if (!m.isActive()) throw new BusinessException(m.getName() + " is no longer sold.");
            if (m.isExpired()) throw new BusinessException(m.getName() + " is past its expiry date and can't be sold right now.");
            if (m.getStock() < qty) {
                throw new BusinessException("Only " + m.getStock() + " packs of " + m.getName() + " are left.");
            }
            m.setStock(m.getStock() - qty);

            OrderItem item = new OrderItem();
            item.setMedicine(m);
            item.setMedicineName(m.getName());
            item.setUnitPrice(m.getPrice());
            item.setQuantity(qty);
            item.setLineTotal(m.getPrice().multiply(BigDecimal.valueOf(qty)));
            item.setHsnCode(m.getHsnCode());
            item.setGstRate(m.getGstRate());
            item.setExpiryDate(m.getExpiryDate());
            item.getMembers().addAll(line.members());
            order.addItem(item);
            subtotal = subtotal.add(item.getLineTotal());
        }

        BigDecimal fee = CartService.deliveryFeeFor(subtotal);
        order.setSubtotal(subtotal);
        order.setDeliveryFee(fee);
        order.setTotal(subtotal.add(fee));
        // UPI / card are simulated as paid. Plug a gateway (Razorpay, Stripe ...) in here.
        order.setPaymentStatus(paymentMethod == PaymentMethod.COD ? PaymentStatus.PENDING : PaymentStatus.PAID);
        return orders.save(order);
    }

    private String newOrderNumber() {
        return "MZ" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"))
                + String.format("%05d", new Random().nextInt(100_000));
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public Order updateStatus(Long orderId, OrderStatus next) {
        Order o = orders.findById(orderId).orElseThrow(() -> new BusinessException("Order not found."));
        if (!o.getStatus().canMoveTo(next)) {
            throw new BusinessException("An order that is " + o.getStatus().getLabel().toLowerCase()
                    + " can't be marked " + next.getLabel().toLowerCase() + ".");
        }
        o.setStatus(next);
        o.setUpdatedAt(LocalDateTime.now());

        if (next == OrderStatus.SHIPPED) invoiceService.issue(o);
        if (next == OrderStatus.DELIVERED) {
            invoiceService.issue(o);   // no-op unless it skipped "shipped" before invoices existed
            o.setDeliveredAt(LocalDateTime.now());
            if (o.getPaymentStatus() == PaymentStatus.PENDING) o.setPaymentStatus(PaymentStatus.PAID);
            planService.onOrderDelivered(o);   // tops up supply at home
        }
        if (next == OrderStatus.CANCELLED) {
            o.getItems().forEach(i -> i.getMedicine().setStock(i.getMedicine().getStock() + i.getQuantity()));
            if (o.getPaymentStatus() == PaymentStatus.PAID) o.setPaymentStatus(PaymentStatus.REFUNDED);
        }

        notifications.notify(o.getUser(), NotificationType.ORDER_UPDATE,
                "Order " + next.getLabel().toLowerCase(),
                "Order " + o.getOrderNumber() + " is now " + next.getLabel().toLowerCase() + "."
                        + (next == OrderStatus.SHIPPED ? " Your tax invoice " + o.getInvoiceNumber() + " is ready on the order page." : "")
                        + (next == OrderStatus.DELIVERED ? " Tell us how it went with a quick review." : ""),
                "/orders/" + o.getId());
        return o;
    }

    @Transactional
    public void cancelByCustomer(Long orderId, User user) {
        Order o = getForUser(orderId, user);
        if (!o.isCancellable()) throw new BusinessException("This order has shipped and can't be cancelled.");
        updateStatus(o.getId(), OrderStatus.CANCELLED);
    }

    /** "Buy again": copies an old order's items into the cart, skipping what's unavailable. */
    @Transactional
    public int reorderToCart(Long orderId, User user) {
        Order o = getForUser(orderId, user);
        int added = 0;
        for (OrderItem i : o.getItems()) {
            try {
                cartService.add(user, i.getMedicine().getId(), i.getQuantity(),
                        i.getMembers().stream().filter(x -> !x.isRemoved()).toList());
                added++;
            } catch (BusinessException ignored) {
                // out of stock or discontinued: skip it
            }
        }
        return added;
    }

    // ------------------------------------------------------------------ queries

    public List<Order> forUser(User user) { return orders.findByUserOrderByCreatedAtDesc(user); }

    public List<Order> recentForUser(User user) { return orders.findTop5ByUserOrderByCreatedAtDesc(user); }

    public Order getForUser(Long id, User user) {
        return orders.findByIdAndUser(id, user).orElseThrow(() -> new BusinessException("Order not found."));
    }

    public Order get(Long id) { return orders.findById(id).orElseThrow(() -> new BusinessException("Order not found.")); }

    /** Customer orders for the admin pipeline, optionally narrowed to one status. */
    public List<Order> adminList(OrderStatus status) {
        return status == null ? orders.findByUserRoleOrderByCreatedAtDesc(Role.CUSTOMER)
                : orders.findByUserRoleAndStatusOrderByCreatedAtDesc(Role.CUSTOMER, status);
    }

    /** Number of customer orders in each status, in pipeline order. */
    public Map<OrderStatus, Long> adminCounts() {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) counts.put(s, orders.countByUserRoleAndStatus(Role.CUSTOMER, s));
        return counts;
    }
}
