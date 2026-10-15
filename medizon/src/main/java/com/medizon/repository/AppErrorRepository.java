package com.medizon.repository;

import com.medizon.domain.AppError;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AppErrorRepository extends JpaRepository<AppError, Long> {
    Optional<AppError> findByFingerprint(String fingerprint);
    List<AppError> findTop100ByResolvedOrderByLastSeenDesc(boolean resolved);
    long countByResolvedFalse();
    long countByResolvedFalseAndLastSeenAfter(LocalDateTime since);

    @Modifying
    @Query("update AppError e set e.resolved = true, e.resolvedAt = :now where e.resolved = false")
    int resolveAll(@Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from AppError e where e.resolved = true and e.lastSeen < :before")
    int deleteResolvedBefore(@Param("before") LocalDateTime before);
}
