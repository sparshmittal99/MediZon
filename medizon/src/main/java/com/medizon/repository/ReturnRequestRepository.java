package com.medizon.repository;

import com.medizon.domain.Order;
import com.medizon.domain.OrderItem;
import com.medizon.domain.ReturnRequest;
import com.medizon.domain.ReturnStatus;
import com.medizon.domain.Store;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {
    List<ReturnRequest> findByOrderOrderByCreatedAtDesc(Order order);
    List<ReturnRequest> findByUserOrderByCreatedAtDesc(User user);
    Optional<ReturnRequest> findByIdAndUser(Long id, User user);
    List<ReturnRequest> findAllByOrderByCreatedAtDesc();
    List<ReturnRequest> findByStatusOrderByCreatedAtAsc(ReturnStatus status);
    long countByStatus(ReturnStatus status);
    List<ReturnRequest> findByOrderStoreOrderByCreatedAtDesc(Store store);
    List<ReturnRequest> findByOrderStoreAndStatusOrderByCreatedAtAsc(Store store, ReturnStatus status);
    long countByOrderStoreAndStatus(Store store, ReturnStatus status);
    List<ReturnRequest> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(LocalDateTime from, LocalDateTime to);

    /** Packs of an order line already returned or on their way back (anything not rejected). */
    @Query("select coalesce(sum(i.quantity), 0) from ReturnItem i where i.orderItem = :item and i.request.status <> :rejected")
    long returnedQuantity(@Param("item") OrderItem item, @Param("rejected") ReturnStatus rejected);

    @Query("select coalesce(sum(r.refundAmount), 0) from ReturnRequest r where r.status = :refunded")
    BigDecimal totalRefunded(@Param("refunded") ReturnStatus refunded);

    List<ReturnRequest> findByStatusAndRefundDueOnLessThanEqual(ReturnStatus status, LocalDate day);
}
