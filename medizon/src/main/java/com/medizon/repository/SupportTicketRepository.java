package com.medizon.repository;

import com.medizon.domain.Order;
import com.medizon.domain.SupportTicket;
import com.medizon.domain.TicketStatus;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    Optional<SupportTicket> findByIdAndUser(Long id, User user);

    List<SupportTicket> findByUserOrderByUpdatedAtDesc(User user);

    List<SupportTicket> findByUserAndOrderOrderByUpdatedAtDesc(User user, Order order);

    long countByUserAndStatusNot(User user, TicketStatus status);

    List<SupportTicket> findByStatusInOrderByUpdatedAtDesc(Collection<TicketStatus> statuses);

    List<SupportTicket> findTop300ByOrderByUpdatedAtDesc();

    @Query("select avg(t.rating) from SupportTicket t where t.rating is not null")
    Double averageRating();

    @Query("select count(t) from SupportTicket t where t.user = :user and t.createdAt > :since")
    long countCreatedSince(@Param("user") User user, @Param("since") java.time.LocalDateTime since);
}
