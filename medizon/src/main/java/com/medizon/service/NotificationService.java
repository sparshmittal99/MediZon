package com.medizon.service;

import com.medizon.domain.Member;
import com.medizon.domain.Notification;
import com.medizon.domain.NotificationDevice;
import com.medizon.domain.NotificationType;
import com.medizon.domain.User;
import com.medizon.repository.NotificationDeviceRepository;
import com.medizon.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every alert goes through here. It is stored for the in-app bell, picked up by the
 * browser poller for desktop notifications, and important ones are also emailed.
 * Add SMS / WhatsApp / FCM push by extending {@link #notify}.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Set<NotificationType> EMAILED = EnumSet.of(
            NotificationType.DOSE_REMINDER, NotificationType.REORDER_ALERT,
            NotificationType.AUTO_REORDER, NotificationType.PRESCRIPTION_UPDATE, NotificationType.RETURN_UPDATE,
            NotificationType.EXPIRY_ALERT, NotificationType.ERROR_ALERT, NotificationType.SUPPORT_UPDATE,
            NotificationType.SUPPORT_TICKET, NotificationType.BACK_IN_STOCK);

    private static final Duration ALERT_WINDOW = Duration.ofMinutes(15);

    /** Customers keep their newest 20 notifications; older ones are deleted as new ones arrive. */
    public static final int CUSTOMER_KEEP = 20;

    private final NotificationRepository notifications;
    private final EmailService email;
    private final NotificationPush push;
    private final PushService phones;
    private final NotificationDeviceRepository devices;

    @Value("${medizon.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * The same notification as a phone notification on each Android app signed in to this account that follows the
     * member it's about, once it's saved. Dose reminders are left out: the app rings those itself.
     */
    private void pushToPhones(Notification n) {
        if (!phones.isEnabled() || n.getType() == NotificationType.DOSE_REMINDER || n.getUser() == null) return;
        List<NotificationDevice> targets = devices.findByUserAndPushTokenIsNotNull(n.getUser()).stream()
                .filter(d -> d.allows(n.getMember())).toList();
        if (targets.isEmpty()) return;
        Map<String, String> data = Map.of(
                "nid", String.valueOf(n.getId()),
                "type", n.getType().name(),
                "title", n.getTitle() == null ? "MediZon" : n.getTitle(),
                "body", n.getMessage() == null ? "" : n.getMessage(),
                "link", n.getLink() == null ? "" : n.getLink());
        Runnable send = () -> targets.forEach(d -> phones.send(d.getPushToken(), data));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    @Transactional
    public Notification notify(User user, NotificationType type, String title, String message, String link) {
        return notify(user, type, title, message, link, null);
    }

    /** {@code member} tags alerts about one family member, so each device can choose whose alerts it pops up. */
    @Transactional
    public Notification notify(User user, NotificationType type, String title, String message, String link, Member member) {
        return notify(user, type, title, message, link, member, true);
    }

    /** {@code emailIt = false} keeps the alert to the app, browser and phones only. */
    @Transactional
    public Notification notify(User user, NotificationType type, String title, String message, String link, Member member,
                               boolean emailIt) {
        Notification n = new Notification();
        n.setUser(user);
        n.setMember(member);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setLink(link);
        notifications.save(n);
        trim(user);
        push.signal(user.getId());

        // Send email for all notifications if the user has an email address.
        // Dedicated order placement and status milestones are handled separately with rich templates by OrderEmailService.
        boolean isDedicatedOrderEmail = (type == NotificationType.ORDER_UPDATE && title != null &&
                (title.equalsIgnoreCase("Order placed") || title.equalsIgnoreCase("Orders placed") ||
                 title.toLowerCase().startsWith("order ")));

        // Dose reminders aren't emailed: they come in the app, the browser and as phone alarms
        boolean noEmail = !emailIt || isDedicatedOrderEmail || type == NotificationType.DOSE_REMINDER;
        pushToPhones(n);

        // Email service is strictly for customers; admin and staff do not receive email alerts
        if (!noEmail && user != null && user.isCustomer() && user.getEmail() != null && !user.getEmail().isBlank()) {
            String actionUrl = (link != null && !link.isBlank()) ? baseUrl.replaceAll("/+$", "") + link : null;
            String typeLabel = switch (type) {
                case DOSE_REMINDER -> "Dose Reminder";
                case REORDER_ALERT, AUTO_REORDER -> "Refill Alert";
                case PRESCRIPTION_UPDATE -> "Prescription";
                case RETURN_UPDATE -> "Return & Refund";
                case EXPIRY_ALERT -> "Medicine Expiry";
                case BACK_IN_STOCK -> "Back in Stock";
                case SUPPORT_UPDATE, SUPPORT_TICKET -> "Support Desk";
                case ERROR_ALERT -> "System Alert";
                case ORDER_UPDATE -> "Order & Payment";
                case MAINTENANCE -> "Scheduled Maintenance";
                case ANNOUNCEMENT -> "Announcement";
                case INFO -> (title != null && title.toLowerCase().contains("password")) ? "Security Alert" : "Update";
                default -> "Notification";
            };

            java.util.Map<String, Object> variables = java.util.Map.of(
                    "user", user,
                    "title", title != null ? title : "Notification",
                    "message", message != null ? message : "",
                    "typeLabel", typeLabel,
                    "actionUrl", actionUrl != null ? actionUrl : "",
                    "baseUrl", baseUrl
            );

            String fallback = "Hi " + (user.getFirstName() != null ? user.getFirstName() : "there") + ",\n\n"
                    + (message != null ? message : "")
                    + (actionUrl != null ? "\n\nOpen MediZon: " + actionUrl : "")
                    + "\n\n- MediZon";

            email.sendTemplate(senderFor(type, title), user.getEmail(), "MediZon: " + title, "emails/notification", variables, fallback);
        }
        return n;
    }

    /**
     * Which address an emailed alert comes from: support@ for the support desk, refund@ for anything refunded,
     * return@ for returns, payments@ for money received, store@ for other order news, noreply@ for the rest.
     */
    static EmailService.Sender senderFor(NotificationType type, String title) {
        String t = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case SUPPORT_UPDATE, SUPPORT_TICKET -> EmailService.Sender.SUPPORT;
            case RETURN_UPDATE -> t.contains("refund") ? EmailService.Sender.REFUNDS : EmailService.Sender.RETURNS;
            case ORDER_UPDATE -> t.contains("refund") ? EmailService.Sender.REFUNDS
                    : t.startsWith("payment") || t.startsWith("paid") ? EmailService.Sender.PAYMENTS
                    : EmailService.Sender.STORE;
            default -> EmailService.Sender.NOREPLY;
        };
    }

    /** The notification centre: a customer's newest 20 (all they keep), staff's newest 50. */
    @Transactional(readOnly = true)
    public List<Notification> recent(User user) {
        List<Notification> list = notifications.findTop50ByUserOrderByCreatedAtDesc(user);
        return user.isStaff() ? list : list.stream().limit(CUSTOMER_KEEP).toList();
    }

    /** Deletes a customer's notifications past the newest {@link #CUSTOMER_KEEP}. */
    @Transactional
    public void trim(User user) {
        if (user.isStaff()) return;
        List<Long> old = notifications.idsNewestFirst(user, PageRequest.of(1, CUSTOMER_KEEP));
        while (!old.isEmpty()) {
            notifications.deleteAllByIdInBatch(old);
            old = notifications.idsNewestFirst(user, PageRequest.of(1, CUSTOMER_KEEP));
        }
    }

    public long unreadCount(User user) { return notifications.countByUserAndReadFalse(user); }

    /** Unread count for the bell on one device: members it doesn't follow aren't counted. */
    public long unreadCount(User user, NotificationDevice device) {
        if (device == null || device.isAllMembers()) return unreadCount(user);
        Set<Long> ids = device.getMemberIds();
        return ids.isEmpty() ? notifications.countUnreadWithoutMember(user) : notifications.countUnreadFor(user, ids);
    }

    /** Returns the link to follow after marking the notification read. */
    @Transactional
    public String open(Long id, User user) {
        return notifications.findByIdAndUser(id, user).map(n -> {
            n.setRead(true);
            return n.getLink() == null ? "/notifications" : n.getLink();
        }).orElse("/notifications");
    }

    @Transactional
    public void markAllRead(User user) { notifications.markAllRead(user); }

    /**
     * Unread notifications newer than {@code afterId} from the last {@link #ALERT_WINDOW}. Each browser tab passes
     * the newest id it has seen, so every open tab and device gets each alert, not just whichever polled first.
     * The window keeps a tab opened later from replaying old alerts.
     */
    @Transactional(readOnly = true)
    public List<Notification> newSince(User user, long afterId) {
        return notifications.findByUserAndReadFalseAndIdGreaterThanAndCreatedAtAfterOrderByIdAsc(
                user, afterId, LocalDateTime.now().minus(ALERT_WINDOW));
    }

    /** As above, minus alerts about members this device doesn't follow. */
    @Transactional(readOnly = true)
    public List<Notification> newSince(User user, long afterId, NotificationDevice device) {
        return newSince(user, afterId).stream().filter(n -> device == null || device.allows(n.getMember())).toList();
    }

    public long latestId(User user) { return notifications.latestId(user); }
}
