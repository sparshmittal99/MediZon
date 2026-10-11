package com.medizon.repository;

import com.medizon.domain.DoseLog;
import com.medizon.domain.MedicationPlan;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DoseLogRepository extends JpaRepository<DoseLog, Long> {
    boolean existsByPlanAndScheduledAt(MedicationPlan plan, LocalDateTime scheduledAt);
    Optional<DoseLog> findByPlanAndScheduledAt(MedicationPlan plan, LocalDateTime scheduledAt);
    List<DoseLog> findByPlanUserAndScheduledAtBetween(User user, LocalDateTime from, LocalDateTime to);
    void deleteByPlan(MedicationPlan plan);
}
