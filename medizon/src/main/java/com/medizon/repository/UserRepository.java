package com.medizon.repository;

import com.medizon.domain.Role;
import com.medizon.domain.Store;
import com.medizon.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByGoogleSubject(String googleSubject);

    /** Just what the sign-in check needs on every request (SignInGuard), without loading the whole account. */
    interface AccountState {
        Long getId();
        boolean isEnabled();
        boolean isDeleted();
        int getRememberEpoch();
    }

    Optional<AccountState> findStateByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    long countByRole(Role role);
    long countByRoleAndCreatedAtAfter(Role role, LocalDateTime since);
    List<User> findByRole(Role role);
    List<User> findByCreatedAtAfter(LocalDateTime since);
    List<User> findTop6ByRoleOrderByCreatedAtDesc(Role role);
    List<User> findAllByOrderByCreatedAtDesc();
    List<User> findByStoreOrderByFullNameAsc(Store store);
    List<User> findByRoleAndDeletedFalseAndEnabledTrue(Role role);
    List<User> findByRoleAndStoreAndDeletedFalseAndEnabledTrue(Role role, Store store);

    /** Holds the account's row until the transaction ends, so two payments from one wallet happen one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") Long id);
}
