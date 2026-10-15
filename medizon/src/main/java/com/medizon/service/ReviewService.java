package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.ReviewRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviews;
    private final UserRepository users;
    private final OrderService orderService;
    private final NotificationService notifications;
    private final StaffAlerts staffAlerts;

    /** Customers can review their own order once, after it's delivered. Admins are notified. */
    @Transactional
    public Review submit(Long orderId, User user, int rating, String comment) {
        Order order = orderService.getForUser(orderId, user);
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException("You can review an order once it's delivered.");
        }
        if (reviews.existsByOrder(order)) throw new BusinessException("You've already reviewed this order.");
        if (rating < 1 || rating > 5) throw new BusinessException("Choose a rating from 1 to 5 stars.");

        Review r = new Review();
        r.setOrder(order);
        r.setUser(user);
        r.setRating(rating);
        r.setComment(comment == null || comment.isBlank() ? null : comment.trim());
        reviews.save(r);

        String stars = rating + " star" + (rating == 1 ? "" : "s");
        staffAlerts.notify(Role.SUPPORT, NotificationType.INFO, "New review: " + stars,
                user.getFullName() + " rated order " + order.getOrderNumber() + " " + stars + ".", "/admin/reviews");
        return r;
    }

    public Optional<Review> forOrder(Order order) { return reviews.findByOrder(order); }

    public List<Review> list(Integer rating) {
        return rating == null ? reviews.findAllNewestFirst() : reviews.findByRatingNewestFirst(rating);
    }

    public Double averageRating() { return reviews.averageRating(); }

    public long count() { return reviews.count(); }

    /** Review count for each star, 5 down to 1. */
    public Map<Integer, Long> distribution() {
        Map<Integer, Long> d = new LinkedHashMap<>();
        for (int s = 5; s >= 1; s--) d.put(s, 0L);
        for (Object[] row : reviews.countByRating()) d.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        return d;
    }
}
