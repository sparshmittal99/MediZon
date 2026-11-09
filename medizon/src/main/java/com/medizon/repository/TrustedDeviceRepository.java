package com.medizon.repository;

import com.medizon.domain.TrustedDevice;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {
    Optional<TrustedDevice> findByUserAndDeviceKey(User user, String deviceKey);

    Optional<TrustedDevice> findByUser_IdAndDeviceKey(Long userId, String deviceKey);

    long countByUser(User user);

    @Modifying
    @Query("delete from TrustedDevice t where t.user = :user")
    int deleteAllOf(@Param("user") User user);

    /** Every trusted device of the account but {@code keepKey}. */
    @Modifying
    @Query("delete from TrustedDevice t where t.user = :user and t.deviceKey <> :keepKey")
    int deleteAllExcept(@Param("user") User user, @Param("keepKey") String keepKey);

    @Modifying
    @Query("delete from TrustedDevice t where t.user = :user and t.deviceKey = :deviceKey")
    int deleteDevice(@Param("user") User user, @Param("deviceKey") String deviceKey);
}
