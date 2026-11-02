package com.medizon.repository;

import com.medizon.domain.PasswordResetToken;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    Optional<PasswordResetToken> findByUserAndToken(User user, String token);

    /** True when a token was issued so recently that it still expires after the given time. */
    boolean existsByUserAndExpiresAtAfter(User user, LocalDateTime time);

    /** Deletes any previous tokens for this user before issuing a new one. */
    void deleteByUser(User user);

    /** Housekeeping: removes tokens that have already expired. */
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :now")
    void deleteExpiredBefore(LocalDateTime now);
}

