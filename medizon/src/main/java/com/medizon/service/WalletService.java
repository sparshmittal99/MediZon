package com.medizon.service;

import com.medizon.domain.User;
import com.medizon.domain.WalletTransaction;
import com.medizon.repository.UserRepository;
import com.medizon.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * MediZon Money: a balance refunds can be paid into instantly and later orders can be paid with, in full or in part.
 * The balance is the sum of the ledger; every movement locks the account row first, so two payments can't both spend
 * the same rupees.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletTransactionRepository transactions;
    private final UserRepository users;

    public BigDecimal balance(User user) {
        if (user == null || user.getId() == null) return BigDecimal.ZERO.setScale(2);
        return transactions.balance(user).setScale(2, RoundingMode.HALF_UP);
    }

    public List<WalletTransaction> history(User user) { return transactions.findByUserOrderByCreatedAtDescIdDesc(user); }

    @Transactional
    public WalletTransaction credit(User user, BigDecimal amount, String description, String link) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Credit must be positive");
        return move(user, amount, description, link);
    }

    /** Spends up to {@code wanted} and returns what was actually taken: never more than the balance. */
    @Transactional
    public BigDecimal spend(User user, BigDecimal wanted, String description, String link) {
        if (wanted == null || wanted.signum() <= 0) return BigDecimal.ZERO.setScale(2);
        users.lockById(user.getId());
        BigDecimal take = wanted.min(balance(user)).setScale(2, RoundingMode.HALF_UP);
        if (take.signum() <= 0) return BigDecimal.ZERO.setScale(2);
        move(user, take.negate(), description, link);
        return take;
    }

    private WalletTransaction move(User user, BigDecimal amount, String description, String link) {
        users.lockById(user.getId());
        WalletTransaction t = new WalletTransaction();
        t.setUser(user);
        t.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        t.setBalanceAfter(balance(user).add(t.getAmount()));
        t.setDescription(description.length() > 200 ? description.substring(0, 199) + "…" : description);
        t.setLink(link);
        return transactions.save(t);
    }
}
