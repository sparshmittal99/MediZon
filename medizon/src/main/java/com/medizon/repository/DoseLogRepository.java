package com.medizon.repository;

import com.medizon.domain.DoseLog;
import com.medizon.domain.DoseStatus;
import com.medizon.domain.MedicationPlan;
import com.medizon.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DoseLogRepository extends JpaRepository<DoseLog, Long> {
    boolean existsByPlanAndScheduledAt(MedicationPlan plan, LocalDateTime scheduledAt);
    Optional<DoseLog> findByPlanAndScheduledAt(MedicationPlan plan, LocalDateTime scheduledAt);
    List<DoseLog> findByPlanUserAndScheduledAtBetween(User user, LocalDateTime from, LocalDateTime to);
    void deleteByPlan(MedicationPlan plan);

    // ---- dose history, newest first ----
    List<DoseLog> findTop10ByPlanOrderByScheduledAtDesc(MedicationPlan plan);
    Page<DoseLog> findByPlanAndScheduledAtBetweenOrderByScheduledAtDesc(MedicationPlan plan, LocalDateTime from,
                                                                         LocalDateTime to, Pageable page);
    Page<DoseLog> findByPlanAndStatusAndScheduledAtBetweenOrderByScheduledAtDesc(MedicationPlan plan, DoseStatus status,
                                                                                  LocalDateTime from, LocalDateTime to, Pageable page);
    long countByPlanAndStatusAndScheduledAtBetween(MedicationPlan plan, DoseStatus status, LocalDateTime from, LocalDateTime to);
    Optional<DoseLog> findFirstByPlanOrderByScheduledAtAsc(MedicationPlan plan);
    List<DoseLog> findByPlanAndScheduledAtAfter(MedicationPlan plan, LocalDateTime after);
}
