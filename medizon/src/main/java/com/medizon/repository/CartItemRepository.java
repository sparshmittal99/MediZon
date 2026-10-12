package com.medizon.repository;

import com.medizon.domain.CartItem;
import com.medizon.domain.Medicine;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByUserOrderByIdAsc(User user);
    Optional<CartItem> findByUserAndMedicine(User user, Medicine medicine);
    Optional<CartItem> findByIdAndUser(Long id, User user);
    long countByUser(User user);

    @Query("select c from CartItem c where c.members is empty")
    List<CartItem> findWithoutMembers();
    void deleteByUser(User user);
}
