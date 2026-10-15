package com.medizon.service;

import com.medizon.domain.Feedback;
import com.medizon.domain.NotificationType;
import com.medizon.domain.Role;
import com.medizon.domain.User;
import com.medizon.repository.FeedbackRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackRepository feedback;
    private final UserRepository users;
    private final NotificationService notifications;
    private final StaffAlerts staffAlerts;

    /** Stores customer feedback and tells the admin and support. */
    @Transactional
    public Feedback submit(User user, String message, String category, Integer rating) {
        if (user.isStaff()) throw new BusinessException("Feedback comes from customer accounts.");
        if (message == null || message.isBlank()) throw new BusinessException("The feedback is empty.");
        String m = message.trim();
        Feedback f = new Feedback();
        f.setUser(user);
        f.setMessage(m.length() > 1000 ? m.substring(0, 1000) : m);
        f.setCategory(category == null || category.isBlank() ? "General" : category);
        f.setRating(rating != null && rating >= 1 && rating <= 5 ? rating : null);
        feedback.save(f);
        staffAlerts.notify(Role.SUPPORT, NotificationType.INFO, "New feedback: " + f.getCategory(),
                user.getFullName() + ": " + abbreviate(f.getMessage()), "/admin/reviews#feedback");
        return f;
    }

    public List<Feedback> all() { return feedback.findAllNewestFirst(); }

    private static String abbreviate(String s) { return s.length() <= 140 ? s : s.substring(0, 139) + "…"; }
}
