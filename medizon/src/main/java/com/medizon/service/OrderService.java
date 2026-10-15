package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.CartSummary;
import com.medizon.dto.Offer;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.OrderRepository;
import com.medizon.repository.PrescriptionRepository;
import com.medizon.repository.StoreItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orders. Each order comes from one store: at checkout every line goes to the nearest store delivering to the address
 * that has it in stock, so a cart can become several orders, placed and paid for together (they share a checkout
 * reference). The delivery fee is worked out on the whole cart and charged once.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orders;
    private final MedicineRepository medicines;
    private final StoreItemRepository storeItems;
    private final PrescriptionRepository prescriptions;
    private final CartService cartService;
    private final CatalogService catalog;
    private final NotificationService notifications;
    private final StaffAlerts staffAlerts;
    private final MedicationPlanService planService;
    private final InvoiceService invoiceService;
    private final DeliveryAreaService deliveryAreas;
    private final AddressService addressService;

    /** One order line: packs of a medicine and how many packs each person gets. */
    private record Line(Long medicineId, int quantity, Map<Member, Integer> packs) {}

    // ------------------------------------------------------------------ customer checkout

    /** Places the cart as one order per store; returns them, the biggest first. */
    @Transactional
    public List<Order> checkout(User user, PaymentMethod paymentMethod, Long prescriptionId, Long addressId) {
        if (user.isStaff()) throw new BusinessException("Staff accounts can't place orders.");
        if (addressId == null) throw new BusinessException("Choose a delivery address.");
        Address ship = addressService.get(addressId, user);
        CartSummary cart = cartService.summary(user, ship.getPincode());
        if (cart.isEmpty()) throw new BusinessException("Your cart is empty.");
        if (!cart.getUnavailable().isEmpty()) {
            throw new BusinessException(cart.getUnavailable().stream().map(i -> i.getMedicine().getName()).collect(Collectors.joining(", "))
                    + " can't be delivered to " + ship.getPincode() + " right now. Remove " + (cart.getUnavailable().size() == 1 ? "it" : "them")
                    + " from the cart or choose another address.");
        }

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
            lines.add(new Line(i.getMedicine().getId(), i.getQuantity(), i.getAllocation()));
        }

        List<Order> placed = createOrders(user, lines, paymentMethod, rx, OrderSource.CUSTOMER, ship);
        cartService.clear(user);
        if (placed.size() == 1) {
            Order o = placed.get(0);
            notifications.notify(user, NotificationType.ORDER_UPDATE, "Order placed",
                    "Order " + o.getOrderNumber() + " for ₹" + o.getTotal() + " is placed.", "/orders/" + o.getId());
        } else {
            BigDecimal total = placed.stream().map(Order::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            notifications.notify(user, NotificationType.ORDER_UPDATE, "Orders placed",
                    "Your cart of ₹" + total + " comes from " + placed.size() + " stores, so it's " + placed.size() + " orders: "
                            + placed.stream().map(o -> o.getOrderNumber() + " (" + o.getStore().getName() + ")").collect(Collectors.joining(", ")) + ".",
                    "/orders");
        }
        placed.forEach(this::alertStore);
        return placed;
    }

    private void alertStore(Order o) {
        staffAlerts.notifyStore(o.getStore(), false, NotificationType.ORDER_UPDATE, "New order " + o.getOrderNumber(),
                o.getItemCount() + " pack(s), ₹" + o.getTotal() + (o.isAutoReorder() ? " (automatic refill)." : "."),
                "/admin/orders/" + o.getId());
    }

    // ------------------------------------------------------------------ auto refill

    /** Why an auto-refill can't be placed right now, or empty if it can. Never throws. */
    public Optional<String> autoReorderBlocker(MedicationPlan plan) {
        User user = plan.getUser();
        Medicine m = plan.getMedicine();
        Address ship = refillAddress(plan);
        if (ship == null) return Optional.of("there's no saved delivery address");
        if (!deliveryAreas.isServiceable(ship.getPincode())) return Optional.of("we don't deliver to PIN code " + ship.getPincode() + " yet");
        if (!m.isActive()) return Optional.of(m.getName() + " is no longer sold on MediZon");
        Offer offer = catalog.offer(m, ship.getPincode(), Math.max(1, plan.getReorderPacks()));
        if (!offer.isListed()) return Optional.of(m.getName() + " isn't sold by the stores that deliver to " + ship.getPincode());
        if (!offer.canSell(Math.max(1, plan.getReorderPacks()))) return Optional.of(m.getName() + " is out of stock for delivery to " + ship.getPincode());
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
        int packs = Math.max(1, plan.getReorderPacks());
        Map<Member, Integer> forWhom = plan.getMember() == null ? Map.of() : Map.of(plan.getMember(), packs);
        Order o = createOrders(user, List.of(new Line(m.getId(), packs, forWhom)),
                PaymentMethod.COD, rx, OrderSource.AUTO_REORDER, refillAddress(plan)).get(0);
        alertStore(o);
        return o;
    }

    /** The address chosen on the reminder for automatic refills, else the default address. */
    private Address refillAddress(MedicationPlan plan) {
        return plan.getRefillAddress() != null ? plan.getRefillAddress()
                : addressService.defaultFor(plan.getUser()).orElse(null);
    }

    private Optional<Prescription> findValidPrescription(User user) {
        return prescriptions.findByUserAndStatusOrderByReviewedAtDesc(user, PrescriptionStatus.APPROVED)
                .stream().filter(Prescription::isValid).findFirst();
    }

    // ------------------------------------------------------------------ shared core

    /**
     * Places the lines as one order per store: each line goes to the nearest store delivering to {@code ship} that
     * has enough in stock. {@code ship} is the saved address to deliver to; without one, the account's address.
     */
    private List<Order> createOrders(User user, List<Line> lines, PaymentMethod paymentMethod,
                                     Prescription rx, OrderSource source, Address ship) {
        if (ship == null && !user.hasAddress()) throw new BusinessException("Add a delivery address first.");
        String pincode = ship != null ? ship.getPincode() : user.getPincode();
        deliveryAreas.requireServiceable(pincode);

        Map<Medicine, Integer> wanted = new LinkedHashMap<>();
        Map<Long, Medicine> byId = new HashMap<>();
        for (Line line : lines) {
            Medicine m = medicines.findById(line.medicineId())
                    .orElseThrow(() -> new BusinessException("A medicine in your order no longer exists."));
            if (!m.isActive()) throw new BusinessException(m.getName() + " is no longer sold.");
            wanted.merge(m, line.quantity(), Integer::sum);
            byId.put(m.getId(), m);
        }
        Map<Long, Offer> offers = catalog.offers(wanted, pincode);

        // Which store supplies each line, nearest first
        Map<Long, List<Line>> byStore = new LinkedHashMap<>();
        Map<Long, Store> stores = new HashMap<>();
        for (Line line : lines) {
            Offer o = offers.get(line.medicineId());
            Medicine m = byId.get(line.medicineId());
            if (!o.canSell(wanted.get(m))) {
                throw new BusinessException(!o.isListed() ? m.getName() + " isn't sold by the stores that deliver to " + pincode + "."
                        : o.stock() == 0 ? m.getName() + " is out of stock for delivery to " + pincode + "."
                        : "Only " + o.stock() + " packs of " + m.getName() + " are available for delivery to " + pincode + ".");
            }
            byStore.computeIfAbsent(o.store().getId(), k -> new ArrayList<>()).add(line);
            stores.put(o.store().getId(), o.store());
        }

        String ref = byStore.size() > 1 ? newCheckoutRef() : null;
        List<Order> placed = new ArrayList<>();
        for (Map.Entry<Long, List<Line>> group : byStore.entrySet()) {
            Order order = newOrder(user, paymentMethod, source, ship);
            order.setStore(stores.get(group.getKey()));
            order.setCheckoutRef(ref);
            BigDecimal subtotal = BigDecimal.ZERO;
            boolean needsRx = false;
            for (Line line : group.getValue()) {
                Offer offer = offers.get(line.medicineId());
                StoreItem si = storeItems.lockById(offer.item().getId())
                        .orElseThrow(() -> new BusinessException("A medicine in your order is no longer listed."));
                Medicine m = si.getMedicine();
                int qty = line.quantity();
                if (!si.isSellable() || si.getStock() < qty) {
                    throw new BusinessException("Only " + Math.max(0, si.getStock()) + " packs of " + m.getName() + " are left. Try again.");
                }
                si.setStock(si.getStock() - qty);

                OrderItem item = new OrderItem();
                item.setMedicine(m);
                item.setMedicineName(m.getName());
                item.setUnitPrice(si.getSellingPrice());
                item.setQuantity(qty);
                item.setLineTotal(si.getSellingPrice().multiply(BigDecimal.valueOf(qty)));
                item.setHsnCode(m.getHsnCode());
                item.setGstRate(m.getGstRate());
                item.setExpiryDate(si.getExpiryDate());
                item.setReturnableAtOrder(m.isReturnable());
                line.packs().forEach((who, n) -> {
                    item.getMembers().add(who);
                    item.getPacks().put(who.getId(), n);
                });
                order.addItem(item);
                subtotal = subtotal.add(item.getLineTotal());
                needsRx |= m.isPrescriptionRequired();
            }
            order.setPrescription(needsRx ? rx : null);
            order.setSubtotal(subtotal);
            placed.add(order);
        }

        // One delivery fee for the whole cart, on the biggest order
        BigDecimal cartSubtotal = placed.stream().map(Order::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        placed.sort(Comparator.comparing(Order::getSubtotal).reversed());
        BigDecimal fee = CartService.deliveryFeeFor(cartSubtotal);
        for (int k = 0; k < placed.size(); k++) {
            Order o = placed.get(k);
            o.setDeliveryFee(k == 0 ? fee : BigDecimal.ZERO);
            BigDecimal exact = o.getSubtotal().add(o.getDeliveryFee());
            o.setRoundOff(RoundOff.of(exact));
            o.setTotal(exact.add(o.getRoundOff()));
        }
        return orders.saveAll(placed);
    }

    private Order newOrder(User user, PaymentMethod paymentMethod, OrderSource source, Address ship) {
        Order order = new Order();
        order.setOrderNumber(newOrderNumber());
        order.setUser(user);
        order.setPaymentMethod(paymentMethod);
        order.setSource(source);
        if (ship != null) {
            order.setShippingAddress(ship.getFormatted());
            order.setContactPhone(ship.getReceiverPhone());
            order.setShippingState(ship.getState());
            order.setReceiverName(ship.getReceiverName());
            order.setAddressLabel(ship.getLabel());
        } else {
            order.setShippingAddress(user.getFormattedAddress());
            order.setContactPhone(user.getPhone());
            order.setShippingState(user.getState());
            order.setReceiverName(user.getFullName());
        }
        if (user.hasBillingAddress()) {
            order.setBillingName(user.getBilling().getName());
            order.setBillingAddress(user.getBilling().getFormatted());
            order.setBillingGstin(user.getBilling().getGstin());
        } else {
            order.setBillingName(user.getFullName());   // the buyer, even when the parcel goes to someone else
            order.setBillingAddress(order.getShippingAddress());
        }
        // UPI / card are simulated as paid. Plug a gateway (Razorpay, Stripe ...) in here.
        order.setPaymentStatus(paymentMethod == PaymentMethod.COD ? PaymentStatus.PENDING : PaymentStatus.PAID);
        return order;
    }

    private String newOrderNumber() {
        return "MZ" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"))
                + String.format("%05d", new Random().nextInt(100_000));
    }

    private String newCheckoutRef() {
        return "CK" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + String.format("%04d", new Random().nextInt(10_000));
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public Order updateStatus(Long orderId, OrderStatus next) {
        Order o = orders.findById(orderId).orElseThrow(() -> new NotFoundException("Order not found."));
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
            o.getItems().forEach(i -> restock(o, i.getMedicine(), i.getQuantity()));
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

    /** Puts packs back on the shelf of the store that sold them. */
    public void restock(Order o, Medicine m, int packs) {
        if (o.getStore() == null) return;
        storeItems.findByStoreAndMedicine(o.getStore(), m).ifPresent(si -> si.setStock(si.getStock() + packs));
    }

    @Transactional
    public void cancelByCustomer(Long orderId, User user) {
        Order o = getForUser(orderId, user);
        if (!o.isCancellable()) throw new BusinessException("This order has shipped and can't be cancelled.");
        updateStatus(o.getId(), OrderStatus.CANCELLED);
    }

    /** "Buy again": copies an old order's items into the cart, skipping what's unavailable at {@code pincode}. */
    @Transactional
    public int reorderToCart(Long orderId, User user, String pincode) {
        Order o = getForUser(orderId, user);
        int added = 0;
        for (OrderItem i : o.getItems()) {
            try {
                // Same packs for the same people, as far as they're still on the account
                List<Member> who = i.getSortedMembers().stream().filter(x -> !x.isRemoved()).toList();
                if (who.isEmpty()) {
                    cartService.add(user, i.getMedicine().getId(), i.getQuantity(), pincode);
                } else {
                    int[] share = i.shareOf(who, i.getQuantity());
                    for (int k = 0; k < who.size(); k++) {
                        if (share[k] > 0) cartService.add(user, i.getMedicine().getId(), share[k], List.of(who.get(k)), pincode);
                    }
                }
                added++;
            } catch (BusinessException ignored) {
                // out of stock or not sold here: skip it
            }
        }
        return added;
    }

    // ------------------------------------------------------------------ queries

    public List<Order> forUser(User user) { return orders.findByUserOrderByCreatedAtDesc(user); }

    public List<Order> recentForUser(User user) { return orders.findTop5ByUserOrderByCreatedAtDesc(user); }

    public Order getForUser(Long id, User user) {
        return orders.findByIdAndUser(id, user).orElseThrow(() -> new NotFoundException("Order not found."));
    }

    public Order get(Long id) { return orders.findById(id).orElseThrow(() -> new NotFoundException("Order not found.")); }

    /** The other orders placed in the same checkout as this one. */
    public List<Order> placedWith(Order o) {
        if (o.getCheckoutRef() == null) return List.of();
        return orders.findByCheckoutRefOrderByIdAsc(o.getCheckoutRef()).stream().filter(x -> !x.getId().equals(o.getId())).toList();
    }

    /** Customer orders for the back office, optionally one store's and one status. */
    public List<Order> adminList(OrderStatus status, Store store) {
        if (store != null) {
            return status == null ? orders.findByStoreOrderByCreatedAtDesc(store)
                    : orders.findByStoreAndStatusOrderByCreatedAtDesc(store, status);
        }
        return status == null ? orders.findByUserRoleOrderByCreatedAtDesc(Role.CUSTOMER)
                : orders.findByUserRoleAndStatusOrderByCreatedAtDesc(Role.CUSTOMER, status);
    }

    /** Number of orders in each status, in pipeline order, for one store or all. */
    public Map<OrderStatus, Long> adminCounts(Store store) {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) {
            counts.put(s, store != null ? orders.countByStoreAndStatus(store, s) : orders.countByUserRoleAndStatus(Role.CUSTOMER, s));
        }
        return counts;
    }
}
