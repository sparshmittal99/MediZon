package com.medizon.repository;

import com.medizon.domain.CheckoutPayment;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CheckoutPaymentRepository extends JpaRepository<CheckoutPayment, Long> {
    Optional<CheckoutPayment> findByGatewayOrderIdAndUser(String gatewayOrderId, User user);

    /** Payments still open since before {@code before}, for the check that nothing paid is left without orders. */
    @Query("select p from CheckoutPayment p join fetch p.user where p.status in :statuses and p.createdAt < :before")
    List<CheckoutPayment> findOpenBefore(@Param("statuses") Collection<CheckoutPayment.Status> statuses, @Param("before") LocalDateTime before);

    /** Moves a payment from one status to another only if it's still in the first; 1 when this caller won. */
    @Modifying
    @Transactional
    @Query("update CheckoutPayment p set p.status = :to, p.updatedAt = CURRENT_TIMESTAMP where p.id = :id and p.status = :from")
    int moveStatus(@Param("id") Long id, @Param("from") CheckoutPayment.Status from, @Param("to") CheckoutPayment.Status to);
}
