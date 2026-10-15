package com.medizon.service;

import com.medizon.domain.NotificationType;
import com.medizon.domain.Role;
import com.medizon.domain.Store;
import com.medizon.domain.User;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tells the staff who handle something about it: the admin always, plus the accounts of the role that does the work
 * (pharmacists for prescriptions, support for reviews and feedback), and for store work only that store's accounts.
 */
@Service
@RequiredArgsConstructor
public class StaffAlerts {

    private final UserRepository users;
    private final NotificationService notifications;

    /** The admin and every active account with {@code role}. */
    public void notify(Role role, NotificationType type, String title, String message, String link) {
        Map<Long, User> to = new LinkedHashMap<>();
        users.findByRoleAndDeletedFalseAndEnabledTrue(Role.ADMIN).forEach(u -> to.put(u.getId(), u));
        if (role != Role.ADMIN) users.findByRoleAndDeletedFalseAndEnabledTrue(role).forEach(u -> to.put(u.getId(), u));
        to.values().forEach(u -> notifications.notify(u, type, title, message, link));
    }

    /** The accounts of one store; with {@code alsoAdmin}, the admin too. */
    public void notifyStore(Store store, boolean alsoAdmin, NotificationType type, String title, String message, String link) {
        Map<Long, User> to = new LinkedHashMap<>();
        if (alsoAdmin) users.findByRoleAndDeletedFalseAndEnabledTrue(Role.ADMIN).forEach(u -> to.put(u.getId(), u));
        if (store != null) users.findByRoleAndStoreAndDeletedFalseAndEnabledTrue(Role.STORE, store).forEach(u -> to.put(u.getId(), u));
        to.values().forEach(u -> notifications.notify(u, type, title, message, link));
    }
}
