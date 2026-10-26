package com.medizon.repository;

import com.medizon.domain.Order;
import com.medizon.domain.OrderSource;
import com.medizon.domain.OrderStatus;
import com.medizon.domain.Role;
import com.medizon.domain.Store;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {
    List<Order> findByUserOrderByCreatedAtDesc(User user);
    List<Order> findTop5ByUserOrderByCreatedAtDesc(User user);
    Optional<Order> findByIdAndUser(Long id, User user);
    List<Order> findTop8ByUserRoleOrderByCreatedAtDesc(Role role);
    List<Order> findByUserRoleOrderByCreatedAtDesc(Role role);
    List<Order> findByUserRoleAndStatusOrderByCreatedAtDesc(Role role, OrderStatus status);
    long countByUserRoleAndStatus(Role role, OrderStatus status);
    long countByStatus(OrderStatus status);
    List<Order> findByStoreOrderByCreatedAtDesc(Store store);
    List<Order> findByStoreAndStatusOrderByCreatedAtDesc(Store store, OrderStatus status);
    long countByStoreAndStatus(Store store, OrderStatus status);
    List<Order> findTop8ByStoreOrderByCreatedAtDesc(Store store);
    List<Order> findByCheckoutRefOrderByIdAsc(String checkoutRef);

    /** When the first order was placed, for the years the Orders filter offers. */
    @Query("select min(o.createdAt) from CustomerOrder o")
    LocalDateTime firstCreatedAt();

    @Query("select min(o.createdAt) from CustomerOrder o where o.user = :user")
    LocalDateTime firstCreatedAt(@Param("user") User user);

    /** Checkouts (a split cart counts once) on which the customer used this promo since {@code since}, cancelled ones left out. */
    @Query("""
            select count(distinct coalesce(o.checkoutRef, o.orderNumber)) from CustomerOrder o
            where o.user = :user and upper(o.couponCode) = upper(:code) and o.createdAt > :since and o.status <> :cancelled""")
    long promoUses(@Param("user") User user, @Param("code") String code, @Param("since") LocalDateTime since,
                   @Param("cancelled") OrderStatus cancelled);

    /** Per promo code: checkouts it was used on and rupees it took off, cancelled orders left out. */
    @Query("""
            select upper(o.couponCode), count(distinct coalesce(o.checkoutRef, o.orderNumber)), coalesce(sum(o.discount), 0)
            from CustomerOrder o where o.couponCode is not null and o.status <> :cancelled group by upper(o.couponCode)""")
    List<Object[]> promoTotals(@Param("cancelled") OrderStatus cancelled);
    List<Order> findByUser(User user);
    long countBySource(OrderSource source);
    long countByCreatedAtAfter(LocalDateTime since);
    List<Order> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(LocalDateTime from, LocalDateTime to);

    /**
     * Rows of [medicineId, Member] for every line of the account's orders in {@code status}; the member is null on
     * lines from before medicines were bought for a named person (those were for the account holder).
     */
    @Query("select i.medicine.id, m from CustomerOrder o join o.items i left join i.members m where o.user = :user and o.status = :status")
    List<Object[]> medicineMembers(@Param("user") User user, @Param("status") OrderStatus status);

    /** The account's orders containing this medicine since {@code since}, newest first. */
    @Query("select distinct o from CustomerOrder o join o.items i where o.user = :user and i.medicine = :medicine and o.createdAt >= :since order by o.createdAt desc")
    List<Order> withMedicineSince(@Param("user") User user, @Param("medicine") com.medizon.domain.Medicine medicine,
                                  @Param("since") LocalDateTime since);

    /** Rows of [userId, orderCount] for the admin user list. */
    @Query("select o.user.id, count(o) from CustomerOrder o group by o.user.id")
    List<Object[]> countPerUser();

    @Query("select coalesce(sum(o.total), 0) from CustomerOrder o where o.status <> :excluded")
    BigDecimal revenueExcluding(@Param("excluded") OrderStatus excluded);

    @Query("select coalesce(sum(o.total), 0) from CustomerOrder o where o.createdAt >= :since and o.status <> :excluded")
    BigDecimal revenueSince(@Param("since") LocalDateTime since, @Param("excluded") OrderStatus excluded);
}
