package com.medizon.repository;

import com.medizon.domain.MedicationPlan;
import com.medizon.domain.Medicine;
import com.medizon.domain.Member;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MedicationPlanRepository extends JpaRepository<MedicationPlan, Long> {
    List<MedicationPlan> findByUserOrderByActiveDescIdDesc(User user);
    List<MedicationPlan> findByUserAndActiveTrue(User user);
    List<MedicationPlan> findByMemberAndMedicine(Member member, Medicine medicine);
    List<MedicationPlan> findByMember(Member member);
    List<MedicationPlan> findByMemberIsNull();
    List<MedicationPlan> findBySetupRequiredTrue();
    List<MedicationPlan> findByExpiryDateBefore(LocalDate day);
    List<MedicationPlan> findByActiveTrue();
    Optional<MedicationPlan> findByIdAndUser(Long id, User user);
    long countByActiveTrue();
}
