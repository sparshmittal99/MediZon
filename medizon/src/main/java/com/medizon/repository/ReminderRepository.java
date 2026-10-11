package com.medizon.repository;

import com.medizon.domain.Reminder;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    List<Reminder> findByUserAndDoneFalseOrderByDueAtAsc(User user);
    Optional<Reminder> findByIdAndUser(Long id, User user);
    List<Reminder> findBySentFalseAndDoneFalseAndDueAtLessThanEqual(LocalDateTime now);
}
