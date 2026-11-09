package com.medizon.repository;

import com.medizon.domain.SecureAccountToken;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SecureAccountTokenRepository extends JpaRepository<SecureAccountToken, Long> {
    Optional<SecureAccountToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from SecureAccountToken t where t.user = :user")
    int deleteAllOf(@Param("user") User user);

    @Modifying
    @Query("delete from SecureAccountToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
