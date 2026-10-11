package com.medizon.repository;

import com.medizon.domain.Prescription;
import com.medizon.domain.PrescriptionStatus;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {
    List<Prescription> findByUserOrderByUploadedAtDesc(User user);
    List<Prescription> findByUserAndStatusOrderByReviewedAtDesc(User user, PrescriptionStatus status);
    Optional<Prescription> findByIdAndUser(Long id, User user);
    List<Prescription> findByStatusOrderByUploadedAtAsc(PrescriptionStatus status);
    List<Prescription> findTop30ByStatusNotOrderByReviewedAtDesc(PrescriptionStatus status);
    long countByStatus(PrescriptionStatus status);
}
