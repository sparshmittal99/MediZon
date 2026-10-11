package com.medizon.repository;

import com.medizon.domain.MedicationPlan;
import com.medizon.domain.Medicine;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MedicationPlanRepository extends JpaRepository<MedicationPlan, Long> {
    List<MedicationPlan> findByUserOrderByActiveDescIdDesc(User user);
    List<MedicationPlan> findByUserAndActiveTrue(User user);
    List<MedicationPlan> findByUserAndMedicineAndActiveTrue(User user, Medicine medicine);
    List<MedicationPlan> findByActiveTrue();
    Optional<MedicationPlan> findByIdAndUser(Long id, User user);
    long countByActiveTrue();
}
