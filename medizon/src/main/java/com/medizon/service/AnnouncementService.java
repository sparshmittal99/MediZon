package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.AnnouncementRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Admin announcements (sales, discounts, updates ...) to every customer. The admin picks the channels: email,
 * notification (bell, browser and the Android app), or both. Scheduled downtime stays with {@link MaintenanceService},
 * which always uses both.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcements;
    private final UserRepository users;
    private final NotificationService notifications;
    private final EmailService email;

    @Value("${medizon.base-url:http://localhost:8080}")
    private String baseUrl;

    public List<Announcement> recent() { return announcements.findTop30ByOrderBySentAtDesc(); }

    public Announcement send(AnnouncementKind kind, String title, String message, String promoCode, String link,
                             boolean viaEmail, boolean viaNotification, String sentBy) {
        if (kind == null) throw new BusinessException("Choose what the announcement is about.");
        title = trim(title);
        message = trim(message);
        promoCode = trim(promoCode);
        link = trim(link);
        if (title == null) throw new BusinessException("Add a title.");
        if (title.length() > 80) throw new BusinessException("Keep the title to 80 characters.");
        if (message == null) throw new BusinessException("Add a message.");
        if (message.length() > 500) throw new BusinessException("Keep the message to 500 characters.");
        if (promoCode != null) {
            promoCode = promoCode.toUpperCase();
            if (!promoCode.matches("[A-Z0-9]{3,20}")) throw new BusinessException("A promo code is 3 to 20 letters or digits.");
        }
        if (link != null && (!link.startsWith("/") || link.startsWith("//") || link.length() > 200)) {
            throw new BusinessException("The link has to be a page on MediZon, starting with /, e.g. /medicines.");
        }
        if (!viaEmail && !viaNotification) throw new BusinessException("Choose how to send it: email, notification, or both.");

        Announcement a = new Announcement();
        a.setKind(kind);
        a.setTitle(title);
        a.setMessage(message);
        a.setPromoCode(promoCode);
        a.setLink(link);
        a.setViaEmail(viaEmail);
        a.setViaNotification(viaNotification);
        a.setSentBy(sentBy);

        String text = fullMessage(a);
        List<User> customers = users.findByRoleAndDeletedFalseAndEnabledTrue(Role.CUSTOMER);
        int told = 0;
        for (User u : customers) {
            try {
                if (viaNotification) {
                    // emails it too when both are chosen, with the same template every other alert uses
                    notifications.notify(u, NotificationType.ANNOUNCEMENT, title, text, link, null, viaEmail);
                } else {
                    emailOnly(u, a, text);
                }
                told++;
            } catch (Exception e) {
                log.warn("Couldn't send announcement to {}: {}", u.getId(), e.getMessage());
            }
        }
        a.setRecipients(told);
        announcements.save(a);
        log.info("Announcement '{}' ({}): sent to {} customer(s)", title, kind, told);
        return a;
    }

    /** The message plus the promo code, as customers read it. */
    static String fullMessage(Announcement a) {
        return a.getPromoCode() == null ? a.getMessage()
                : a.getMessage() + " Use code " + a.getPromoCode() + " at checkout.";
    }

    private void emailOnly(User u, Announcement a, String text) {
        if (u.getEmail() == null || u.getEmail().isBlank()) return;
        String root = baseUrl.replaceAll("/+$", "");
        String actionUrl = a.getLink() == null ? "" : root + a.getLink();
        Map<String, Object> vars = Map.of(
                "user", u,
                "title", a.getTitle(),
                "message", text,
                "typeLabel", a.getKind().getLabel(),
                "actionUrl", actionUrl,
                "baseUrl", baseUrl);
        String fallback = "Hi " + (u.getFirstName() != null ? u.getFirstName() : "there") + ",\n\n" + text
                + (actionUrl.isEmpty() ? "" : "\n\nOpen MediZon: " + actionUrl) + "\n\n- MediZon";
        email.sendTemplate(u.getEmail(), "MediZon: " + a.getTitle(), "emails/notification", vars, fallback);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
