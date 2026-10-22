package com.medizon.repository;

import com.medizon.domain.Notification;
import com.medizon.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findTop50ByUserOrderByCreatedAtDesc(User user);

    /** Ids of the account's notifications, newest first; a page past the ones kept finds those to delete. */
    @Query("select n.id from Notification n where n.user = :user order by n.id desc")
    List<Long> idsNewestFirst(@Param("user") User user, Pageable page);
    Optional<Notification> findByIdAndUser(Long id, User user);
    long countByUserAndReadFalse(User user);

    /** Unread notifications newer than the id a browser tab has already seen, within a recent window. */
    List<Notification> findByUserAndReadFalseAndIdGreaterThanAndCreatedAtAfterOrderByIdAsc(User user, Long afterId,
                                                                                         LocalDateTime since);

    /** Unread count as one device sees it: alerts not about a member, plus those about the given members. */
    @Query("select count(n) from Notification n left join n.member m where n.user = :user and n.read = false"
            + " and (m is null or m.id in :memberIds)")
    long countUnreadFor(@Param("user") User user, @Param("memberIds") Collection<Long> memberIds);

    @Query("select count(n) from Notification n where n.user = :user and n.read = false and n.member is null")
    long countUnreadWithoutMember(@Param("user") User user);

    @Query("select coalesce(max(n.id), 0) from Notification n where n.user = :user")
    long latestId(@Param("user") User user);

    @Modifying
    @Query("update Notification n set n.read = true where n.user = :user and n.read = false")
    int markAllRead(@Param("user") User user);
}
