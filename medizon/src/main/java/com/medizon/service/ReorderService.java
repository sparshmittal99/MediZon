package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.MedicationPlanRepository;
import com.medizon.repository.OrderRepository;
import com.medizon.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Decides when a customer is running low and either alerts them (MANUAL)
 * or places the refill order itself (AUTO). One alert / order per low-supply
 * cycle: {@code reorderPending} is cleared when the refill is delivered.
 * An automatic refill that couldn't be placed (no prescription, out of stock ...)
 * is retried on every check without alerting again, and goes through once the cause is fixed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReorderService {

    private final MedicationPlanRepository plans;
    private final OrderRepository orders;
    private final PrescriptionRepository prescriptions;
    private final OrderService orderService;
    private final NotificationService notifications;
    private final PlatformTransactionManager txManager;

    @Transactional
    public void evaluate(Long planId) {
        plans.findById(planId).ifPresent(this::evaluate);
    }

    /**
     * A newly approved prescription may unblock automatic refills. Runs before the cart is filled from it, so the
     * refills take the packs they need first and the cart gets what's left.
     */
    @org.springframework.core.annotation.Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener
    public void onPrescriptionApproved(PrescriptionService.Approved e) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            tx.executeWithoutResult(s -> prescriptions.findById(e.prescriptionId()).ifPresent(rx ->
                    plans.findByUserAndActiveTrue(rx.getUser()).stream()
                            .filter(p -> p.getReorderMode() == ReorderMode.AUTO && p.isReorderPending() && p.getRefillOrder() == null)
                            .forEach(this::evaluate)));
        } catch (RuntimeException ex) {
            log.error("Refill check after prescription {} was approved failed", e.prescriptionId(), ex);
        }
    }

    private void evaluate(MedicationPlan p) {
        if (!p.isActive() || p.getReorderMode() == ReorderMode.OFF || !p.isRunningLow()) return;
        if (p.isReorderPending()) {
            if (p.getRefillOrder() == null || p.getRefillOrder().getStatus() == OrderStatus.CANCELLED) findRefillOrder(p);
            // Already ordered (or the customer cancelled it), or only an alert was due: nothing more this cycle
            if (p.getRefillOrder() != null || p.getReorderMode() != ReorderMode.AUTO) return;
        }

        User user = p.getUser();
        String supply = p.getDaysLeftRounded() == 0
                ? "less than a day"
                : "about " + p.getDaysLeftRounded() + " day" + (p.getDaysLeftRounded() == 1 ? "" : "s");

        String title = p.getTitle();
        String link = MedicationPlanService.planLink(p);
        if (p.getReorderMode() == ReorderMode.AUTO) {
            Optional<OrderService.ReorderBlocker> blocker = orderService.autoReorderBlocker(p);
            if (blocker.isEmpty()) {
                Order o = orderService.placeAutoReorder(p);
                p.setRefillOrder(o);
                p.setReorderIssue(null);
                p.setLastReorderAt(LocalDateTime.now());
                notifications.notify(user, NotificationType.AUTO_REORDER, "Refill ordered: " + title,
                        "There's " + supply + " of " + title + " left, so we ordered " + p.getReorderPacks()
                                + " pack(s). Order " + o.getOrderNumber() + ", ₹" + o.getTotal()
                                + ", pay on delivery. You can cancel it before it ships.",
                        "/orders/" + o.getId(), p.getMember());
                log.info("Auto-reorder {} placed for plan {}", o.getOrderNumber(), p.getId());
            } else {
                p.setReorderIssue(blocker.get().status());
                if (!p.isReorderPending()) {      // alert once; later checks just retry
                    p.setLastReorderAt(LocalDateTime.now());
                    notifications.notify(user, NotificationType.REORDER_ALERT, "Refill " + title + " yourself",
                            "There's " + supply + " of " + title + " left. We couldn't order it automatically because "
                                    + blocker.get().reason() + ". Fix that or refill manually.", link, p.getMember());
                }
            }
        } else {
            p.setLastReorderAt(LocalDateTime.now());
            notifications.notify(user, NotificationType.REORDER_ALERT, "Running low on " + title,
                    "There's " + supply + " of " + title + " left (runs out around " + p.getRunOutDate()
                            + "). Refill now so no dose is missed.", link, p.getMember());
        }
        p.setReorderPending(true);
    }

    /** An order with this medicine placed since the alert (e.g. by hand after it), preferring one that isn't cancelled. */
    private void findRefillOrder(MedicationPlan p) {
        if (p.getLastReorderAt() == null) return;
        List<Order> found = orders.withMedicineSince(p.getUser(), p.getMedicine(), p.getLastReorderAt().minusMinutes(1));
        found.stream().filter(o -> o.getStatus() != OrderStatus.CANCELLED).findFirst()
                .or(() -> found.stream().findFirst())
                .ifPresent(o -> {
                    p.setRefillOrder(o);
                    p.setReorderIssue(null);
                });
    }
}
