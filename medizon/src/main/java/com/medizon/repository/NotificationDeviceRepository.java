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
}
