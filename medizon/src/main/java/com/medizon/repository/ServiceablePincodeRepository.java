package com.medizon.repository;

import com.medizon.domain.ServiceablePincode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceablePincodeRepository extends JpaRepository<ServiceablePincode, Long> {
    Optional<ServiceablePincode> findByPincode(String pincode);
    boolean existsByPincodeAndActiveTrue(String pincode);
    long countByActiveTrue();
    List<ServiceablePincode> findAllByOrderByPincodeAsc();
}
