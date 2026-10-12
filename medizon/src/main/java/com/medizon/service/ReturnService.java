package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.ReturnRequestRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Returns: the customer picks packs from a delivered order, gives a reason and photos; an admin approves or rejects;
 * approved returns are picked up (supply at home goes down, packs optionally go back on the shelf) and refunded.
 */
@Service
@RequiredArgsConstructor
public class ReturnService {

    public static final int RETURN_WINDOW_DAYS = 7;
    public static final int MAX_IMAGES = 3;

    private final ReturnRequestRepository returns;
    private final OrderService orderService;
    private final MedicationPlanService planService;
    private final FileStorageService storage;
    private final NotificationService notifications;
    private final UserRepository users;

    // ------------------------------------------------------------------ customer

    /** Last day a return can be requested. */
    public LocalDate returnBy(Order o) {
        return o.getDeliveredAt() == null ? null : o.getDeliveredAt().toLocalDate().plusDays(RETURN_WINDOW_DAYS);
    }

    /** Why the order can't be returned right now, or empty if it can. */
    public Optional<String> blocker(Order o) {
        if (o.getStatus() != OrderStatus.DELIVERED) return Optional.of("Returns open once the order is delivered.");
        if (LocalDate.now().isAfter(returnBy(o))) {
            return Optional.of("The return window closed on " + returnBy(o).format(DateTimeFormatter.ofPattern("d MMM yyyy")) + ".");
        }
        if (returns.findByOrderOrderByCreatedAtDesc(o).stream().anyMatch(ReturnRequest::isOpen)) {
            return Optional.of("A return for this order is already in progress.");
        }
        if (returnable(o).values().stream().allMatch(q -> q == 0)) return Optional.of("Everything in this order has been returned.");
        return Optional.empty();
    }

    /** Packs of each order line (by id) that can still be returned. */
    public Map<Long, Integer> returnable(Order o) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (OrderItem i : o.getItems()) {
            long done = returns.returnedQuantity(i, ReturnStatus.REJECTED);
            out.put(i.getId(), (int) Math.max(0, i.getQuantity() - done));
        }
        return out;
    }

    @Transactional
    public ReturnRequest request(User user, Long orderId, List<Long> itemIds, List<Integer> quantities,
                                 ReturnReason reason, String details, List<MultipartFile> images) {
        Order o = orderService.getForUser(orderId, user);
        blocker(o).ifPresent(b -> { throw new BusinessException(b); });
        if (reason == null) throw new BusinessException("Choose a reason for the return.");
        String d = details == null ? "" : details.trim();
        if (reason == ReturnReason.OTHER && d.isEmpty()) throw new BusinessException("Tell us what's wrong.");
        if (d.length() > 1000) throw new BusinessException("Keep the description under 1000 characters.");

        List<MultipartFile> photos = images == null ? List.of() : images.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (photos.isEmpty()) throw new BusinessException("Add at least one photo of the medicine and its packaging.");
        if (photos.size() > MAX_IMAGES) throw new BusinessException("Add at most " + MAX_IMAGES + " photos.");

        ReturnRequest r = new ReturnRequest();
        r.setReturnNumber(newReturnNumber());
        r.setOrder(o);
        r.setUser(user);
        r.setReason(reason);
        r.setDetails(d.isEmpty() ? null : d);

        Map<Long, Integer> left = returnable(o);
        Map<Long, OrderItem> byId = new HashMap<>();
        o.getItems().forEach(i -> byId.put(i.getId(), i));
        BigDecimal refund = BigDecimal.ZERO;
        for (int k = 0; itemIds != null && k < itemIds.size(); k++) {
            int qty = quantities != null && k < quantities.size() && quantities.get(k) != null ? quantities.get(k) : 0;
            if (qty <= 0) continue;
            OrderItem item = byId.get(itemIds.get(k));
            if (item == null) throw new BusinessException("That item isn't part of this order.");
            if (qty > left.getOrDefault(item.getId(), 0)) {
                throw new BusinessException("You can return at most " + left.get(item.getId()) + " pack(s) of " + item.getMedicineName() + ".");
            }
            ReturnItem ri = new ReturnItem();
            ri.setOrderItem(item);
            ri.setQuantity(qty);
            ri.setAmount(item.getUnitPrice().multiply(BigDecimal.valueOf(qty)));
            r.addItem(ri);
            refund = refund.add(ri.getAmount());
        }
        if (r.getItems().isEmpty()) throw new BusinessException("Choose the packs you want to return.");
        r.setRefundAmount(refund);

        photos.forEach(f -> r.getImages().add(new ReturnImage(storage.storeReturnImage(f), f.getContentType())));
        returns.save(r);

        notifications.notify(user, NotificationType.RETURN_UPDATE, "Return requested",
                "We got your return request " + r.getReturnNumber() + " for order " + o.getOrderNumber()
                        + ". A pharmacist will review it, usually within a day.", "/orders/" + o.getId());
        users.findByRole(Role.ADMIN).forEach(admin -> notifications.notify(admin, NotificationType.RETURN_UPDATE,
                "Return to review: " + r.getReturnNumber(),
                user.getFullName() + " wants to return " + r.getPackCount() + " pack(s) from order " + o.getOrderNumber()
                        + " (" + reason.getLabel().toLowerCase() + ").", "/admin/returns/" + r.getId()));
        return r;
    }

    public List<ReturnRequest> forOrder(Order o) { return returns.findByOrderOrderByCreatedAtDesc(o); }

    public List<ReturnRequest> forUser(User user) { return returns.findByUserOrderByCreatedAtDesc(user); }

    /** Owners and admins can see a return's photos; anyone else gets "not found". */
    @Transactional(readOnly = true)
    public ReturnImage image(Long returnId, int index, User viewer) {
        ReturnRequest r = viewer.isAdmin() ? get(returnId)
                : returns.findByIdAndUser(returnId, viewer).orElseThrow(() -> new BusinessException("File not found."));
        if (index < 0 || index >= r.getImages().size()) throw new BusinessException("File not found.");
        return r.getImages().get(index);
    }

    public Resource file(ReturnImage image) { return storage.load(image.getStoredFileName()); }

    // ------------------------------------------------------------------ admin

    public ReturnRequest get(Long id) {
        return returns.findById(id).orElseThrow(() -> new BusinessException("Return not found."));
    }

    public List<ReturnRequest> adminList(ReturnStatus status) {
        return status == null ? returns.findAllByOrderByCreatedAtDesc() : returns.findByStatusOrderByCreatedAtAsc(status);
    }

    public Map<ReturnStatus, Long> adminCounts() {
        Map<ReturnStatus, Long> counts = new EnumMap<>(ReturnStatus.class);
        for (ReturnStatus s : ReturnStatus.values()) counts.put(s, returns.countByStatus(s));
        return counts;
    }

    public long countAwaitingReview() { return returns.countByStatus(ReturnStatus.REQUESTED); }

    public BigDecimal totalRefunded() { return returns.totalRefunded(ReturnStatus.REFUNDED); }

    @Transactional
    public ReturnRequest decide(Long id, boolean approve, String note) {
        ReturnRequest r = move(id, approve ? ReturnStatus.APPROVED : ReturnStatus.REJECTED);
        String n = note == null || note.isBlank() ? null : note.trim();
        if (!approve && n == null) throw new BusinessException("Say why the return is rejected; the customer sees this.");
        r.setAdminNote(n);
        r.setDecidedAt(LocalDateTime.now());
        notifyCustomer(r, approve ? "Return approved" : "Return not approved", approve
                ? "Return " + r.getReturnNumber() + " is approved. Keep the packs ready; our delivery partner will pick them up."
                + (n == null ? "" : " Note: " + n)
                : "Return " + r.getReturnNumber() + " couldn't be approved: " + n);
        return r;
    }

    @Transactional
    public ReturnRequest markPickedUp(Long id, boolean restock) {
        ReturnRequest r = move(id, ReturnStatus.PICKED_UP);
        r.setPickedUpAt(LocalDateTime.now());
        for (ReturnItem ri : r.getItems()) {
            planService.onItemsReturned(ri.getOrderItem(), ri.getQuantity());
            if (restock) {
                Medicine m = ri.getOrderItem().getMedicine();
                m.setStock(m.getStock() + ri.getQuantity());
            }
        }
        r.setRestocked(restock);
        notifyCustomer(r, "Return picked up", "We've collected the packs for return " + r.getReturnNumber()
                + ". Your refund of ₹" + r.getRefundAmount() + " is next.");
        return r;
    }

    @Transactional
    public ReturnRequest refund(Long id) {
        ReturnRequest r = move(id, ReturnStatus.REFUNDED);
        r.setRefundedAt(LocalDateTime.now());
        Order o = r.getOrder();
        if (returnable(o).values().stream().allMatch(q -> q == 0)) o.setPaymentStatus(PaymentStatus.REFUNDED);
        notifyCustomer(r, "Refund processed", "₹" + r.getRefundAmount() + " for return " + r.getReturnNumber()
                + " is refunded" + (o.getPaymentMethod() == PaymentMethod.COD ? "." : " to your " + o.getPaymentMethod().getLabel() + ".")
                + " It can take 5 to 7 working days to show up.");
        return r;
    }

    private ReturnRequest move(Long id, ReturnStatus next) {
        ReturnRequest r = get(id);
        if (!r.getStatus().canMoveTo(next)) {
            throw new BusinessException("A return that is " + r.getStatus().getLabel().toLowerCase()
                    + " can't be marked " + next.getLabel().toLowerCase() + ".");
        }
        r.setStatus(next);
        return r;
    }

    private void notifyCustomer(ReturnRequest r, String title, String message) {
        notifications.notify(r.getUser(), NotificationType.RETURN_UPDATE, title, message, "/orders/" + r.getOrder().getId());
    }

    private String newReturnNumber() {
        return "RT" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"))
                + String.format("%05d", new Random().nextInt(100_000));
    }
}
