package com.medizon.repository;

import com.medizon.domain.StockAlert;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {
    Optional<StockAlert> findByUserAndMedicineId(User user, Long medicineId);

    @Query("select a.medicine.id from StockAlert a where a.user = :user")
    List<Long> medicineIds(@Param("user") User user);

    @Query("select a from StockAlert a join fetch a.user where a.medicine.id in :ids")
    List<StockAlert> findForMedicines(@Param("ids") Collection<Long> ids);

    @Query("select distinct a.medicine.id from StockAlert a")
    List<Long> pendingMedicineIds();
}
