package com.medizon.repository;

import com.medizon.domain.Order;
import com.medizon.domain.OrderSource;
import com.medizon.domain.OrderStatus;
import com.medizon.domain.Role;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserOrderByCreatedAtDesc(User user);
    List<Order> findTop5ByUserOrderByCreatedAtDesc(User user);
    Optional<Order> findByIdAndUser(Long id, User user);
    List<Order> findTop8ByUserRoleOrderByCreatedAtDesc(Role role);
    List<Order> findByUserRoleOrderByCreatedAtDesc(Role role);
    List<Order> findByUserRoleAndStatusOrderByCreatedAtDesc(Role role, OrderStatus status);
    long countByUserRoleAndStatus(Role role, OrderStatus status);
    long countByStatus(OrderStatus status);
    long countBySource(OrderSource source);
    long countByCreatedAtAfter(LocalDateTime since);
    List<Order> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(LocalDateTime from, LocalDateTime to);

    /** Rows of [userId, orderCount] for the admin user list. */
    @Query("select o.user.id, count(o) from CustomerOrder o group by o.user.id")
    List<Object[]> countPerUser();

    @Query("select coalesce(sum(o.total), 0) from CustomerOrder o where o.status <> :excluded")
    BigDecimal revenueExcluding(@Param("excluded") OrderStatus excluded);

    @Query("select coalesce(sum(o.total), 0) from CustomerOrder o where o.createdAt >= :since and o.status <> :excluded")
    BigDecimal revenueSince(@Param("since") LocalDateTime since, @Param("excluded") OrderStatus excluded);
}
