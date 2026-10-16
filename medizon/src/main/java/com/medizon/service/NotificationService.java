package com.medizon.service;

import com.medizon.domain.Member;
import com.medizon.domain.Notification;
import com.medizon.domain.NotificationDevice;
import com.medizon.domain.NotificationType;
import com.medizon.domain.User;
import com.medizon.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
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
            NotificationType.AUTO_REORDER, NotificationType.PRESCRIPTION_UPDATE, NotificationType.EXPIRY_ALERT,
            NotificationType.ERROR_ALERT, NotificationType.SUPPORT_UPDATE);

    private static final Duration ALERT_WINDOW = Duration.ofMinutes(15);

    private final NotificationRepository notifications;
    private final EmailService email;
    private final NotificationPush push;

    @Value("${medizon.base-url:http://localhost:8080}")
    private String baseUrl;

    @Transactional
    public Notification notify(User user, NotificationType type, String title, String message, String link) {
        return notify(user, type, title, message, link, null);
    }

    /** {@code member} tags alerts about one family member, so each device can choose whose alerts it pops up. */
    @Transactional
    public Notification notify(User user, NotificationType type, String title, String message, String link, Member member) {
        Notification n = new Notification();
        n.setUser(user);
        n.setMember(member);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setLink(link);
        notifications.save(n);
        push.signal(user.getId());

        if (EMAILED.contains(type)) {
            String body = "Hi " + user.getFirstName() + ",\n\n" + message
                    + (link != null ? "\n\nOpen MediZon: " + baseUrl + link : "")
                    + "\n\n- MediZon";
            email.send(user.getEmail(), "MediZon: " + title, body);
        }
        return n;
    }

    @Transactional(readOnly = true)
    public List<Notification> recent(User user) { return notifications.findTop50ByUserOrderByCreatedAtDesc(user); }

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
