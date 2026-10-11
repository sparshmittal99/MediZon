package com.medizon.repository;

import com.medizon.domain.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    @Query("select f from Feedback f join fetch f.user order by f.createdAt desc")
    List<Feedback> findAllNewestFirst();

    long countByCreatedAtAfter(LocalDateTime since);
}
