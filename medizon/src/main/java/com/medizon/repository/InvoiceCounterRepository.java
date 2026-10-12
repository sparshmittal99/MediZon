package com.medizon.repository;

import com.medizon.domain.InvoiceCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InvoiceCounterRepository extends JpaRepository<InvoiceCounter, String> {

    /** Creates the year's counter if it's the first invoice of the year; safe when two orders ship at once. */
    @Modifying
    @Query(value = "insert into invoice_counters (financial_year, last_number) values (:fy, 0) on conflict do nothing",
            nativeQuery = true)
    void ensureYear(@Param("fy") String financialYear);

    /** Row-locks the counter so concurrent shipments get consecutive, unique numbers. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InvoiceCounter c where c.financialYear = :fy")
    Optional<InvoiceCounter> lock(@Param("fy") String financialYear);
}
