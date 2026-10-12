package com.medizon.repository;

import com.medizon.domain.Member;
import com.medizon.domain.MemberKind;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    List<Member> findByUserAndRemovedFalseOrderByIdAsc(User user);
    Optional<Member> findFirstByUserAndKind(User user, MemberKind kind);
    Optional<Member> findByIdAndUserAndRemovedFalse(Long id, User user);
    long countByUserAndKindAndRemovedFalse(User user, MemberKind kind);
}
