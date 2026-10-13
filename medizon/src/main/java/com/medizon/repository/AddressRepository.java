package com.medizon.repository;

import com.medizon.domain.Address;
import com.medizon.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, Long> {
    /** Default first, then newest. */
    List<Address> findByUserOrderByDefaultAddressDescCreatedAtDesc(User user);
    Optional<Address> findByIdAndUser(Long id, User user);
    Optional<Address> findFirstByUserAndDefaultAddressTrue(User user);
    long countByUser(User user);
}
