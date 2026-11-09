package com.medizon.repository;

import com.medizon.domain.KnownDevice;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnownDeviceRepository extends JpaRepository<KnownDevice, Long> {
    boolean existsByUserAndDeviceKey(User user, String deviceKey);

    boolean existsByUser(User user);
}
