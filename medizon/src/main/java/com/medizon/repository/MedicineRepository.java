package com.medizon.repository;

import com.medizon.domain.Medicine;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MedicineRepository extends JpaRepository<Medicine, Long> {

    /** Empty strings (not nulls) mean "no filter" - avoids PostgreSQL null-parameter typing issues. */
    @Query("""
           select m from Medicine m
           where m.active = true
             and (lower(m.name) like lower(concat('%', :q, '%'))
                  or lower(coalesce(m.genericName, '')) like lower(concat('%', :q, '%'))
                  or lower(coalesce(m.manufacturer, '')) like lower(concat('%', :q, '%')))
             and (:category = '' or m.category = :category)
           """)
    Page<Medicine> search(@Param("q") String q, @Param("category") String category, Pageable pageable);

    @Query("select distinct m.category from Medicine m where m.active = true order by m.category")
    List<String> findActiveCategories();

    List<Medicine> findTop8ByActiveTrueOrderByIdDesc();

    List<Medicine> findByActiveTrueOrderByNameAsc();

    List<Medicine> findByActiveTrueAndStockLessThanOrderByStockAsc(int stock);

    /** Row lock while decrementing stock so two checkouts can't oversell. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Medicine m where m.id = :id")
    Optional<Medicine> lockById(@Param("id") Long id);
}
