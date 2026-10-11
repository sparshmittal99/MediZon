package com.medizon.repository;

import com.medizon.domain.Role;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    long countByRole(Role role);
    long countByRoleAndCreatedAtAfter(Role role, LocalDateTime since);
    List<User> findByRole(Role role);
    List<User> findByCreatedAtAfter(LocalDateTime since);
    List<User> findTop6ByRoleOrderByCreatedAtDesc(Role role);
    List<User> findAllByOrderByCreatedAtDesc();
}
