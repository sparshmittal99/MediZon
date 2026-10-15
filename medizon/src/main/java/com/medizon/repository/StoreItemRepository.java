package com.medizon.repository;

import com.medizon.domain.Medicine;
import com.medizon.domain.Store;
import com.medizon.domain.StoreItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoreItemRepository extends JpaRepository<StoreItem, Long> {

    Optional<StoreItem> findByStoreAndMedicine(Store store, Medicine medicine);

    /** Listings of these medicines in these stores, for working out who delivers what. */
    @Query("select i from StoreItem i where i.medicine.id in :medicineIds and i.store.id in :storeIds")
    List<StoreItem> findFor(@Param("medicineIds") Collection<Long> medicineIds, @Param("storeIds") Collection<Long> storeIds);

    /** Listings of these medicines in any open store, for shoppers who haven't picked a location yet. */
    @Query("select i from StoreItem i where i.medicine.id in :medicineIds and i.store.active = true and i.active = true")
    List<StoreItem> findOpenFor(@Param("medicineIds") Collection<Long> medicineIds);

    @Query("select i from StoreItem i join fetch i.medicine m where i.store = :store order by m.name")
    List<StoreItem> findByStoreOrderByName(@Param("store") Store store);

    List<StoreItem> findByMedicine(Medicine medicine);

    /** Listed items running low in open stores, lowest first. */
    @Query("""
           select i from StoreItem i
           where i.active = true and i.medicine.active = true and i.store.active = true and i.stock < :below
           order by i.stock, i.medicine.name
           """)
    List<StoreItem> lowStock(@Param("below") int below);

    @Query("""
           select i from StoreItem i
           where i.active = true and i.medicine.active = true and i.store = :store and i.stock < :below
           order by i.stock, i.medicine.name
           """)
    List<StoreItem> lowStockIn(@Param("below") int below, @Param("store") Store store);

    /** Row lock while taking stock off, so two checkouts can't oversell. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from StoreItem i where i.id = :id")
    Optional<StoreItem> lockById(@Param("id") Long id);

    long countByStoreAndActiveTrue(Store store);
}
