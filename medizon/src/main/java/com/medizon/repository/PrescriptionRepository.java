package com.medizon.repository;

import com.medizon.domain.Prescription;
import com.medizon.domain.PrescriptionStatus;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {
    List<Prescription> findByUserOrderByUploadedAtDesc(User user);

    @Query("select count(o) from CustomerOrder o where o.prescription = :p")
    long countOrdersWith(@Param("p") Prescription p);

    @Query("select count(u) from PrescriptionUse u where u.prescriptionItem.prescription = :p")
    long countOrderLinesWith(@Param("p") Prescription p);

    /** Some order was placed against it (as the order's prescription, or a line's). */
    default boolean usedForOrders(Prescription p) {
        return countOrdersWith(p) > 0 || countOrderLinesWith(p) > 0;
    }
    List<Prescription> findByUserAndStatusOrderByReviewedAtDesc(User user, PrescriptionStatus status);
    Optional<Prescription> findByIdAndUser(Long id, User user);
    List<Prescription> findByStatusOrderByUploadedAtAsc(PrescriptionStatus status);
    List<Prescription> findTop30ByStatusInOrderByReviewedAtDesc(Collection<PrescriptionStatus> statuses);
    long countByStatus(PrescriptionStatus status);

    /** Files still needed: drafts, pending and approved prescriptions, and ones rejected after {@code since}. */
    @Query("""
            select p.storedFileName from Prescription p where p.removed = false and (p.status in :inUse
              or (p.status = :rejected and (p.reviewedAt is null or p.reviewedAt > :since)))""")
    List<String> fileNamesInUse(@Param("inUse") Collection<PrescriptionStatus> inUse,
                                @Param("rejected") PrescriptionStatus rejected, @Param("since") LocalDateTime since);
}
