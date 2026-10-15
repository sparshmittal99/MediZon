package com.medizon.repository;

import com.medizon.domain.Store;
import com.medizon.domain.StoreArea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoreAreaRepository extends JpaRepository<StoreArea, Long> {

    /** Open stores delivering to a PIN code, quickest (nearest) first. */
    @Query("""
           select a from StoreArea a
           where a.pincode = :pincode and a.store.active = true
           order by a.maxDays, a.minDays, a.store.id
           """)
    List<StoreArea> serving(@Param("pincode") String pincode);

    List<StoreArea> findByStoreOrderByPincodeAsc(Store store);

    List<StoreArea> findByPincodeInOrderByMaxDaysAscMinDaysAsc(Collection<String> pincodes);

    Optional<StoreArea> findByStoreAndPincode(Store store, String pincode);

    long countByStore(Store store);

    void deleteByPincode(String pincode);
}
