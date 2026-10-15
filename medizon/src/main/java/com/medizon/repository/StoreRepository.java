package com.medizon.repository;

import com.medizon.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreRepository extends JpaRepository<Store, Long> {
    List<Store> findAllByOrderByNameAsc();
    List<Store> findByActiveTrueOrderByNameAsc();
    boolean existsByNameIgnoreCase(String name);
}
