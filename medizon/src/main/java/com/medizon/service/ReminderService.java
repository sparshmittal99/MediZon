package com.medizon.service;

import com.medizon.domain.NotificationType;
import com.medizon.domain.Reminder;
import com.medizon.domain.User;
import com.medizon.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** One-off reminders set through the assistant. Each one becomes a notification when it's due. */
@Service
@RequiredArgsConstructor
public class ReminderService {

    private static final int MAX_OPEN = 50;

    private final ReminderRepository reminders;
    private final NotificationService notifications;

    @Transactional
    public Reminder create(User user, String text, LocalDateTime dueAt) {
        if (text == null || text.isBlank()) throw new BusinessException("Tell me what to remind you about.");
        if (dueAt == null || !dueAt.isAfter(LocalDateTime.now().minusMinutes(1))) {
            throw new BusinessException("Pick a time in the future.");
        }
        if (reminders.findByUserAndDoneFalseOrderByDueAtAsc(user).size() >= MAX_OPEN) {
            throw new BusinessException("You have " + MAX_OPEN + " open reminders. Mark some done first.");
        }
        String t = text.trim();
        Reminder r = new Reminder();
        r.setUser(user);
        r.setText(t.length() > 300 ? t.substring(0, 300) : t);
        r.setDueAt(dueAt.withSecond(0).withNano(0));
        return reminders.save(r);
    }

    public List<Reminder> open(User user) { return reminders.findByUserAndDoneFalseOrderByDueAtAsc(user); }

    @Transactional
    public Reminder complete(Long id, User user) {
        Reminder r = reminders.findByIdAndUser(id, user).orElseThrow(() -> new BusinessException("Reminder not found."));
        r.setDone(true);
        return r;
    }

    /** Called every minute by the scheduler. */
    @Transactional
    public int fireDue(LocalDateTime now) {
        List<Reminder> due = reminders.findBySentFalseAndDoneFalseAndDueAtLessThanEqual(now);
        for (Reminder r : due) {
            User u = r.getUser();
            notifications.notify(u, NotificationType.INFO, "Reminder: " + r.getText(),
                    "You asked MediZon Assistant to remind you: " + r.getText() + ".", u.isStaff() ? "/admin" : null);
            r.setSent(true);
        }
        return due.size();
    }
}
