package com.medizon.service;

import com.medizon.domain.Order;
import com.medizon.domain.OrderStatus;
import com.medizon.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Sends rich modern HTML emails for orders:
 * 1. Order confirmation on checkout
 * 2. Status updates (Confirmed, Shipped, Delivered, Cancelled)
 *
 * Orders are passed by id in an event and only read once the change has committed, in a transaction of their own on
 * the mail thread. Handing the live entity to another thread let the template walk its collections while the request
 * was still flushing them (ConcurrentModificationException on the admin's save) or after its session had closed
 * (a lazy-load failure that sent the plain-text fallback instead of the HTML email).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEmailService {

    /** An order was placed. */
    public record Placed(Long orderId) {}

    /** An order moved to {@code status}. */
    public record StatusChanged(Long orderId, OrderStatus status) {}

    private final EmailService emailService;
    private final OrderRepository orders;

    @Value("${medizon.base-url:http://localhost:8080}")
    private String baseUrl;

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onPlaced(Placed e) {
        orders.findById(e.orderId()).ifPresent(this::sendOrderConfirmation);
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onStatusChanged(StatusChanged e) {
        orders.findById(e.orderId()).ifPresent(o -> sendOrderStatusUpdate(o, e.status()));
    }

    /**
     * Sent right after checkout is placed. Includes order number, store name, itemized list,
     * pricing breakdown, payment method, delivery address, and direct order view link.
     */
    private void sendOrderConfirmation(Order order) {
        if (order.getUser() == null || !order.getUser().isCustomer() || order.getUser().getEmail() == null) return;

        String orderUrl = baseUrl.replaceAll("/+$", "") + order.getCustomerPath();
        String subject = "Order Placed: #" + order.getOrderNumber() + " | MediZon";

        Map<String, Object> variables = Map.of(
                "order", order,
                "user", order.getUser(),
                "orderUrl", orderUrl,
                "baseUrl", baseUrl
        );

        String fallbackText = "Hi " + order.getUser().getFirstName() + ",\n\n"
                + "Thank you for your order #" + order.getOrderNumber() + " with MediZon.\n\n"
                + "Order Total: ₹" + order.getTotal() + "\n"
                + "Store: " + (order.getStore() != null ? order.getStore().getName() : "MediZon Pharmacy") + "\n"
                + "Delivery Address: " + order.getShippingAddress() + "\n\n"
                + "Track your order here: " + orderUrl + "\n\n"
                + "Thank you,\nMediZon Pharmacy";

        send(order, subject, "emails/order-confirmation", variables, fallbackText);
    }

    /**
     * Sent when an order status changes (CONFIRMED, SHIPPED, DELIVERED, CANCELLED).
     */
    private void sendOrderStatusUpdate(Order order, OrderStatus nextStatus) {
        if (order.getUser() == null || !order.getUser().isCustomer() || order.getUser().getEmail() == null) return;

        String orderUrl = baseUrl.replaceAll("/+$", "") + order.getCustomerPath();
        String invoiceUrl = baseUrl.replaceAll("/+$", "") + order.getCustomerPath() + "/invoice";

        String kind = order.isExchange() ? "Replacement Order" : "Order";
        String subject = order.isExchange() ? switch (nextStatus) {
            case SHIPPED -> kind + " #" + order.getOrderNumber() + " Shipped | MediZon";
            case OUT_FOR_DELIVERY -> kind + " #" + order.getOrderNumber() + " is Out for Delivery! 🚚 | MediZon";
            case DELIVERED -> kind + " #" + order.getOrderNumber() + " Delivered | MediZon";
            case CANCELLED -> kind + " #" + order.getOrderNumber() + " Cancelled | MediZon";
            default -> "Update on " + kind + " #" + order.getOrderNumber() + " | MediZon";
        } : switch (nextStatus) {
            case CONFIRMED -> "Order #" + order.getOrderNumber() + " Confirmed | MediZon";
            case SHIPPED -> "Order #" + order.getOrderNumber() + " Shipped | MediZon";
            case OUT_FOR_DELIVERY -> "Order #" + order.getOrderNumber() + " is Out for Delivery! 🚚 | MediZon";
            case DELIVERED -> "Order #" + order.getOrderNumber() + " Delivered | MediZon";
            case CANCELLED -> "Order #" + order.getOrderNumber() + " Cancelled | MediZon";
            default -> "Update on Order #" + order.getOrderNumber() + " | MediZon";
        };

        String message = order.isExchange() ? switch (nextStatus) {
            case SHIPPED -> "Your replacement order #" + order.getOrderNumber() + " for exchange " + order.getExchangeNumber()
                    + " has been packed and shipped" + (order.getExpectedBy() == null ? "." : ". Expected by "
                    + order.getExpectedBy().format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM", java.util.Locale.ENGLISH)) + ".");
            case OUT_FOR_DELIVERY -> "Our delivery partner is out for delivery with your replacement and will reach your address shortly."
                    + " Please keep the old packs ready if they haven't been collected yet.";
            case DELIVERED -> "Your replacement order #" + order.getOrderNumber() + " for exchange " + order.getExchangeNumber()
                    + " has been delivered. There was nothing to pay.";
            case CANCELLED -> "Your replacement order #" + order.getOrderNumber() + " has been cancelled."
                    + (order.getCancelReason() != null ? " " + order.getCancelReason() : "");
            default -> "Your replacement order #" + order.getOrderNumber() + " is now " + nextStatus.getLabel() + ".";
        } : switch (nextStatus) {
            case CONFIRMED -> "Your order #" + order.getOrderNumber() + " has been confirmed by "
                    + (order.getStore() != null ? order.getStore().getName() : "the store") + " and is being prepared.";
            case SHIPPED -> "Great news! Your order #" + order.getOrderNumber() + " has been packed and shipped. Your tax invoice is now ready.";
            case OUT_FOR_DELIVERY -> "Our delivery partner is out for delivery with your package and will reach your address shortly.";
            case DELIVERED -> "Your order #" + order.getOrderNumber() + " has been delivered. We hope everything arrived in good shape!";
            case CANCELLED -> "Your order #" + order.getOrderNumber() + " has been cancelled."
                    + (order.getCancelReason() != null ? " " + order.getCancelReason() : "")
                    + " Any applicable refund has been processed.";
            default -> "Your order #" + order.getOrderNumber() + " status is now " + nextStatus.getLabel() + ".";
        };

        Map<String, Object> variables = Map.of(
                "order", order,
                "user", order.getUser(),
                "status", nextStatus,
                "statusMessage", message,
                "orderUrl", orderUrl,
                "invoiceUrl", invoiceUrl,
                "baseUrl", baseUrl
        );

        String fallbackText = "Hi " + order.getUser().getFirstName() + ",\n\n"
                + message + "\n\n"
                + "Order Total: ₹" + order.getTotal() + "\n"
                + "View Order: " + orderUrl + "\n\n"
                + "Regards,\nMediZon";

        send(order, subject, "emails/order-status", variables, fallbackText);
    }

    /** Renders here, while the order's session is open, so only finished strings leave this thread. */
    private void send(Order order, String subject, String template, Map<String, Object> variables, String fallbackText) {
        String to = order.getUser().getEmail();
        try {
            emailService.sendHtml(EmailService.Sender.STORE, to, subject, emailService.render(template, variables), fallbackText);
            log.info("Email '{}' queued for order {}", template, order.getOrderNumber());
        } catch (RuntimeException ex) {
            log.warn("Could not render '{}' for order {}; sending plain text", template, order.getOrderNumber(), ex);
            emailService.send(EmailService.Sender.STORE, to, subject, fallbackText);
        }
    }
}
