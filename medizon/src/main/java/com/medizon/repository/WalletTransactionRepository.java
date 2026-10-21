package com.medizon.repository;

import com.medizon.domain.User;
import com.medizon.domain.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    List<WalletTransaction> findByUserOrderByCreatedAtDescIdDesc(User user);

    @Query("select coalesce(sum(t.amount), 0) from WalletTransaction t where t.user = :user")
    BigDecimal balance(@Param("user") User user);
}
