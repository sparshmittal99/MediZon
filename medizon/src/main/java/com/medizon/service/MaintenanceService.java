package com.medizon.service;

import com.medizon.domain.MaintenanceWindow;
import com.medizon.domain.NotificationType;
import com.medizon.domain.Role;
import com.medizon.domain.User;
import com.medizon.repository.MaintenanceWindowRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Scheduled downtime. The admin sets when; every customer gets a short notification (bell, browser and the Android
 * app) with an email, and the site shows a banner until it's over. Cancelling tells them too.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    static final Duration LONGEST = Duration.ofHours(48);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a");

    private final MaintenanceWindowRepository windows;
    private final UserRepository users;
    private final NotificationService notifications;

    /** Announced windows not over yet; read on every page for the banner, so kept in memory. */
    private volatile List<MaintenanceWindow> upcoming;

    public List<MaintenanceWindow> recent() { return windows.findTop20ByOrderByStartsAtDesc(); }

    @Transactional
    public MaintenanceWindow schedule(LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = LocalDateTime.now();
        if (start == null || end == null) throw new BusinessException("Choose when the downtime starts and ends.");
        if (!start.isAfter(now)) throw new BusinessException("The start has to be in the future.");
        if (!end.isAfter(start)) throw new BusinessException("The end has to be after the start.");
        if (Duration.between(start, end).compareTo(LONGEST) > 0) throw new BusinessException("Keep a downtime to 48 hours or less.");

        MaintenanceWindow w = new MaintenanceWindow();
        w.setStartsAt(start.withSecond(0).withNano(0));
        w.setEndsAt(end.withSecond(0).withNano(0));
        windows.save(w);
        tellCustomers("Scheduled downtime", "MediZon will be down for maintenance " + when(w)
                + ". The site and app may not work during this time.");
        upcoming = null;
        return w;
    }

    @Transactional
    public void cancel(Long id) {
        MaintenanceWindow w = windows.findById(id).orElseThrow(() -> new NotFoundException("Downtime not found."));
        if (w.isCancelled() || w.isOver(LocalDateTime.now())) throw new BusinessException("This downtime is already over or cancelled.");
        w.setCancelled(true);
        tellCustomers("Downtime cancelled", "The maintenance planned " + when(w) + " is cancelled. MediZon works as usual.");
        upcoming = null;
    }

    /** The window to show in the banner: one underway, or the next one coming. */
    public Optional<MaintenanceWindow> current() {
        LocalDateTime now = LocalDateTime.now();
        List<MaintenanceWindow> list = upcoming;
        if (list == null || list.stream().anyMatch(w -> w.isOver(now))) {
            list = windows.findByCancelledFalseAndEndsAtAfterOrderByStartsAtAsc(now);
            upcoming = list;
        }
        return list.stream().filter(w -> !w.isOver(now)).findFirst();
    }

    /** All announced windows not over yet: the Android app shows each once as a phone notification. */
    public List<MaintenanceWindow> announced() {
        current();
        List<MaintenanceWindow> list = upcoming;
        return list == null ? List.of() : list;
    }

    /** Cancelled windows that would still be coming: the Android app shows the cancellation once too. */
    public List<MaintenanceWindow> cancelledUpcoming() {
        return windows.findByCancelledTrueAndEndsAtAfter(LocalDateTime.now());
    }

    /** "on Wed 12 Nov, 2:00 AM – 4:00 AM", or across days "from Wed 12 Nov, 11:00 PM to Thu 13 Nov, 1:00 AM". */
    public static String when(MaintenanceWindow w) {
        String from = w.getStartsAt().format(DAY) + ", " + w.getStartsAt().format(TIME);
        return w.getStartsAt().toLocalDate().equals(w.getEndsAt().toLocalDate())
                ? "on " + from + " – " + w.getEndsAt().format(TIME)
                : "from " + from + " to " + w.getEndsAt().format(DAY) + ", " + w.getEndsAt().format(TIME);
    }

    /** Bell, browser and app notification for every customer; NotificationService also emails it. */
    private void tellCustomers(String title, String message) {
        List<User> customers = users.findByRoleAndDeletedFalseAndEnabledTrue(Role.CUSTOMER);
        for (User u : customers) {
            try {
                notifications.notify(u, NotificationType.MAINTENANCE, title, message, null);
            } catch (Exception e) {
                log.warn("Couldn't tell {} about the downtime: {}", u.getId(), e.getMessage());
            }
        }
        log.info("{}: told {} customer(s)", title, customers.size());
    }
}
