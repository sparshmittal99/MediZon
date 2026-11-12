package com.medizon.repository;

import com.medizon.domain.StockHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface StockHoldRepository extends JpaRepository<StockHold, Long> {

    /** Holds on these listings that haven't expired by {@code now}. */
    @Query("select h from StockHold h where h.storeItemId in :ids and h.expiresAt > :now")
    List<StockHold> findActive(@Param("ids") Collection<Long> storeItemIds, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from StockHold h where h.paymentId = :paymentId")
    int deleteByPayment(@Param("paymentId") Long paymentId);

    @Modifying
    @Transactional
    @Query("delete from StockHold h where h.userId = :userId")
    int deleteByUser(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query("delete from StockHold h where h.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
