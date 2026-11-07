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

    /** The payment for a gateway order, with its customer: the webhook knows only the gateway's order id. */
    @Query("select p from CheckoutPayment p join fetch p.user where p.gatewayOrderId = :gatewayOrderId")
    Optional<CheckoutPayment> findWithUserByGatewayOrderId(@Param("gatewayOrderId") String gatewayOrderId);

    /** A customer's payments in {@code statuses}, oldest first. */
    List<CheckoutPayment> findByUserAndStatusInOrderByIdAsc(User user, Collection<CheckoutPayment.Status> statuses);

    /**
     * What the reconciler checks with the gateway: payments started before {@code createdBefore} and not confirmed yet,
     * and placings stuck since before {@code stuckBefore} (the app stopped half way).
     */
    @Query("select p from CheckoutPayment p join fetch p.user where (p.status = :created and p.createdAt < :createdBefore)"
            + " or (p.status = :placing and p.updatedAt < :stuckBefore) order by p.id")
    List<CheckoutPayment> findToReconcile(@Param("created") CheckoutPayment.Status created, @Param("createdBefore") LocalDateTime createdBefore,
                                          @Param("placing") CheckoutPayment.Status placing, @Param("stuckBefore") LocalDateTime stuckBefore);

    /** Moves a payment from one status to another only if it's still in the first; 1 when this caller won. */
    @Modifying
    @Transactional
    @Query("update CheckoutPayment p set p.status = :to, p.updatedAt = :now where p.id = :id and p.status = :from")
    int moveStatus(@Param("id") Long id, @Param("from") CheckoutPayment.Status from, @Param("to") CheckoutPayment.Status to,
                   @Param("now") LocalDateTime now);

    /** Moves a payment from one status to another only if it's still in the first. Stamped with the app's clock, as
     *  {@code createdAt} is, never the database's (which may be in another time zone). */
    default int moveStatus(Long id, CheckoutPayment.Status from, CheckoutPayment.Status to) {
        return moveStatus(id, from, to, LocalDateTime.now());
    }

    /**
     * Takes over a placing that has really been stuck since before {@code stuckBefore} (the app stopped half way):
     * 1 only for the one caller that wins, and only while it's still stuck, so a placing in progress is never taken.
     */
    @Modifying
    @Transactional
    @Query("update CheckoutPayment p set p.updatedAt = :now where p.id = :id and p.status = :placing and p.updatedAt < :stuckBefore")
    int takeOverStuck(@Param("id") Long id, @Param("placing") CheckoutPayment.Status placing,
                      @Param("stuckBefore") LocalDateTime stuckBefore, @Param("now") LocalDateTime now);
}
