package com.medizon.repository;

import com.medizon.domain.CartItem;
import com.medizon.domain.Medicine;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByUserOrderByIdAsc(User user);
    Optional<CartItem> findByUserAndMedicine(User user, Medicine medicine);
    Optional<CartItem> findByIdAndUser(Long id, User user);
    long countByUser(User user);
    void deleteByUser(User user);
}
