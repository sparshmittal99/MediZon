package com.medizon.repository;

import com.medizon.domain.MaintenanceWindow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, Long> {
    /** Announced and not over yet, soonest first. */
    List<MaintenanceWindow> findByCancelledFalseAndEndsAtAfterOrderByStartsAtAsc(LocalDateTime now);
    List<MaintenanceWindow> findTop20ByOrderByStartsAtDesc();
    /** Cancelled, and would not have been over yet. */
    List<MaintenanceWindow> findByCancelledTrueAndEndsAtAfter(LocalDateTime now);
}
