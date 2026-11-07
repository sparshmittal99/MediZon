package com.medizon.service;

import com.medizon.domain.Order;
import com.medizon.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Email invoice": sends the tax invoice as a PDF to the customer's account email. The PDF and the email body are made
 * on the request thread (they read the order); only the sending is asynchronous. A few sends per order per day.
 */
@Service
@RequiredArgsConstructor
public class InvoiceEmailService {

    /** Sends of one order's invoice allowed in a rolling day. */
    static final int PER_ORDER_PER_DAY = 3;
    private static final Duration WINDOW = Duration.ofDays(1);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final InvoicePdfService pdfService;
    private final EmailService emailService;
    private final Map<Long, Deque<Instant>> sends = new ConcurrentHashMap<>();

    @Value("${medizon.base-url:http://localhost:8080}")
    private String baseUrl;

    /** Emails the invoice to {@code user}'s address and returns that address. */
    public String send(Order order, User user) {
        if (!order.isInvoiceAvailable() || order.getInvoiceNumber() == null) {
            throw new BusinessException("The tax invoice is ready once the order ships.");
        }
        String to = user.getEmail();
        if (to == null || to.isBlank()) throw new BusinessException("Add an email address to your account first.");
        reserve(order.getId());

        byte[] pdf = pdfService.render(order);
        String orderUrl = baseUrl.replaceAll("/+$", "") + order.getCustomerPath();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", user.getFirstName());
        vars.put("invoiceNumber", order.getInvoiceNumber());
        vars.put("orderNumber", order.getOrderNumber());
        vars.put("invoiceDate", order.getInvoicedAt() == null ? "" : order.getInvoicedAt().format(DAY));
        vars.put("total", "₹" + order.getTotal().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        vars.put("orderUrl", orderUrl);
        String html = emailService.render("emails/invoice", vars);
        String text = "Hi " + user.getFirstName() + ",\n\n"
                + "Your MediZon tax invoice " + order.getInvoiceNumber() + " for order " + order.getOrderNumber()
                + " is attached as a PDF.\n\nView the order: " + orderUrl + "\n\nRegards,\nMediZon";
        emailService.sendHtmlWithAttachment(EmailService.Sender.STORE, to, "Tax invoice " + order.getInvoiceNumber() + " | MediZon", html, text,
                InvoicePdfService.fileName(order), pdf, "application/pdf");
        return to;
    }

    /** Counts this send, or refuses it once the order's invoice was emailed {@value #PER_ORDER_PER_DAY} times today. */
    void reserve(Long orderId) {
        Instant now = Instant.now();
        Deque<Instant> times = sends.computeIfAbsent(orderId, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(WINDOW))) times.pollFirst();
            if (times.size() >= PER_ORDER_PER_DAY) {
                throw new BusinessException("This invoice was already emailed " + PER_ORDER_PER_DAY
                        + " times today. Check your inbox and spam folder, or try again tomorrow.");
            }
            times.addLast(now);
        }
    }
}
