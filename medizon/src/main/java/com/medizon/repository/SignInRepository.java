package com.medizon.repository;

import com.medizon.domain.SignIn;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SignInRepository extends JpaRepository<SignIn, Long> {
    Optional<SignIn> findByUserAndDeviceKey(User user, String deviceKey);
    Optional<SignIn> findByUser_IdAndDeviceKey(Long userId, String deviceKey);
    Optional<SignIn> findByIdAndUser(Long id, User user);
    List<SignIn> findByUser(User user);
    boolean existsByUser_IdAndDeviceKeyAndSignedOutAtIsNotNull(Long userId, String deviceKey);
}
