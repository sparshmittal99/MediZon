package com.medizon.repository;

import com.medizon.domain.Cart;
import com.medizon.domain.CartStatus;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findFirstByUserAndStatusOrderByIdAsc(User user, CartStatus status);
    List<Cart> findByUserAndStatus(User user, CartStatus status);
}
