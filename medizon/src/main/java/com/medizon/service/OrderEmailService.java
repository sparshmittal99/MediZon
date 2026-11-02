package com.medizon.service;

import com.medizon.domain.Order;
import com.medizon.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Sends rich modern HTML emails for orders:
 * 1. Order confirmation on checkout
 * 2. Status updates (Confirmed, Shipped, Delivered, Cancelled)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEmailService {

    private final EmailService emailService;

    @Value("${medizon.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * Sent right after checkout is placed. Includes order number, store name, itemized list,
     * pricing breakdown, payment method, delivery address, and direct order view link.
     */
    @Async
    public void sendOrderConfirmation(Order order) {
        if (order == null || order.getUser() == null || !order.getUser().isCustomer() || order.getUser().getEmail() == null) return;

        String orderUrl = baseUrl.replaceAll("/+$", "") + "/orders/" + order.getId();
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

        emailService.sendTemplate(order.getUser().getEmail(), subject, "emails/order-confirmation", variables, fallbackText);
        log.info("Order placed email queued for order {}", order.getOrderNumber());
    }

    /**
     * Sent when an order status changes (CONFIRMED, SHIPPED, DELIVERED, CANCELLED).
     */
    @Async
    public void sendOrderStatusUpdate(Order order, OrderStatus nextStatus) {
        if (order == null || order.getUser() == null || !order.getUser().isCustomer() || order.getUser().getEmail() == null) return;

        String orderUrl = baseUrl.replaceAll("/+$", "") + "/orders/" + order.getId();
        String invoiceUrl = baseUrl.replaceAll("/+$", "") + "/orders/" + order.getId() + "/invoice";

        String subject = switch (nextStatus) {
            case CONFIRMED -> "Order #" + order.getOrderNumber() + " Confirmed | MediZon";
            case SHIPPED -> "Order #" + order.getOrderNumber() + " Shipped | MediZon";
            case OUT_FOR_DELIVERY -> "Order #" + order.getOrderNumber() + " is Out for Delivery! 🚚 | MediZon";
            case DELIVERED -> "Order #" + order.getOrderNumber() + " Delivered | MediZon";
            case CANCELLED -> "Order #" + order.getOrderNumber() + " Cancelled | MediZon";
            default -> "Update on Order #" + order.getOrderNumber() + " | MediZon";
        };

        String message = switch (nextStatus) {
            case CONFIRMED -> "Your order #" + order.getOrderNumber() + " has been confirmed by "
                    + (order.getStore() != null ? order.getStore().getName() : "the store") + " and is being prepared.";
            case SHIPPED -> "Great news! Your order #" + order.getOrderNumber() + " has been packed and shipped. Your tax invoice is now ready.";
            case OUT_FOR_DELIVERY -> "Our delivery partner is out for delivery with your package and will reach your address shortly.";
            case DELIVERED -> "Your order #" + order.getOrderNumber() + " has been delivered. We hope everything arrived in good shape!";
            case CANCELLED -> "Your order #" + order.getOrderNumber() + " has been cancelled. Any applicable refund has been processed.";
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

        emailService.sendTemplate(order.getUser().getEmail(), subject, "emails/order-status", variables, fallbackText);
        log.info("Order status update email ({}) queued for order {}", nextStatus, order.getOrderNumber());
    }
}

