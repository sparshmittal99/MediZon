package com.medizon.repository;

import com.medizon.domain.NotificationDevice;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationDeviceRepository extends JpaRepository<NotificationDevice, Long> {
    Optional<NotificationDevice> findByUserAndDeviceKey(User user, String deviceKey);
    Optional<NotificationDevice> findByIdAndUser(Long id, User user);
    List<NotificationDevice> findByUserOrderByLastSeenAtDesc(User user);
    Optional<NotificationDevice> findByAppTokenHash(String appTokenHash);
    List<NotificationDevice> findByPushToken(String pushToken);
    List<NotificationDevice> findByUserAndPushTokenIsNotNull(User user);
    List<NotificationDevice> findByUser(User user);

    /** The push tokens of Android apps signed in to active customer accounts, each once. */
    @org.springframework.data.jpa.repository.Query("select distinct d.pushToken from NotificationDevice d join d.user u"
            + " where d.pushToken is not null and u.role = :role and u.enabled = true and u.deleted = false")
    List<String> findPushTokens(@org.springframework.data.repository.query.Param("role") com.medizon.domain.Role role);

    /** The push tokens of Android apps signed in to active customer accounts, each once. */
    default List<String> findCustomerPushTokens() { return findPushTokens(com.medizon.domain.Role.CUSTOMER); }
}
