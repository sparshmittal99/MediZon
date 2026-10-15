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
    long countByUser(User user);

    /** Lines from before carts had ids. */
    List<CartItem> findByCartIsNull();

    @Query("select c from CartItem c where c.members is empty")
    List<CartItem> findWithoutMembers();
    void deleteByUser(User user);
}
