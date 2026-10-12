package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.MedicationPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Decides when a customer is running low and either alerts them (MANUAL)
 * or places the refill order itself (AUTO). One alert / order per low-supply
 * cycle: {@code reorderPending} is cleared when the refill is delivered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReorderService {

    private final MedicationPlanRepository plans;
    private final OrderService orderService;
    private final NotificationService notifications;

    @Transactional
    public void evaluate(Long planId) {
        plans.findById(planId).ifPresent(this::evaluate);
    }

    private void evaluate(MedicationPlan p) {
        if (!p.isActive() || p.isReorderPending() || p.getReorderMode() == ReorderMode.OFF || !p.isRunningLow()) return;

        User user = p.getUser();
        String supply = p.getDaysLeftRounded() == 0
                ? "less than a day"
                : "about " + p.getDaysLeftRounded() + " day" + (p.getDaysLeftRounded() == 1 ? "" : "s");

        String title = p.getTitle();
        String link = MedicationPlanService.planLink(p);
        if (p.getReorderMode() == ReorderMode.AUTO) {
            Optional<String> blocker = orderService.autoReorderBlocker(p);
            if (blocker.isEmpty()) {
                Order o = orderService.placeAutoReorder(p);
                notifications.notify(user, NotificationType.AUTO_REORDER, "Refill ordered: " + title,
                        "There's " + supply + " of " + title + " left, so we ordered " + p.getReorderPacks()
                                + " pack(s). Order " + o.getOrderNumber() + ", ₹" + o.getTotal()
                                + ", cash on delivery. You can cancel it before it ships.",
                        "/orders/" + o.getId(), p.getMember());
                log.info("Auto-reorder {} placed for plan {}", o.getOrderNumber(), p.getId());
            } else {
                notifications.notify(user, NotificationType.REORDER_ALERT, "Refill " + title + " yourself",
                        "There's " + supply + " of " + title + " left. We couldn't order it automatically because "
                                + blocker.get() + ". Fix that or refill manually.", link, p.getMember());
            }
        } else {
            notifications.notify(user, NotificationType.REORDER_ALERT, "Running low on " + title,
                    "There's " + supply + " of " + title + " left (runs out around " + p.getRunOutDate()
                            + "). Refill now so no dose is missed.", link, p.getMember());
        }
        p.setReorderPending(true);
        p.setLastReorderAt(LocalDateTime.now());
    }
}
