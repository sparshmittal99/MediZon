package com.medizon.repository;

import com.medizon.domain.Medicine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MedicineRepository extends JpaRepository<Medicine, Long> {

    /** Empty strings (not nulls) mean "no filter" - avoids PostgreSQL null-parameter typing issues. */
    @Query("""
           select m from Medicine m
           where m.active = true
             and (lower(m.name) like lower(concat('%', :q, '%'))
                  or lower(coalesce(m.genericName, '')) like lower(concat('%', :q, '%'))
                  or lower(coalesce(m.manufacturer, '')) like lower(concat('%', :q, '%')))
             and (:category = '' or m.category = :category)
           """)
    Page<Medicine> search(@Param("q") String q, @Param("category") String category, Pageable pageable);

    /** {@link #search} limited to what these stores list: the shop as seen from one PIN code. */
    @Query("""
           select m from Medicine m
           where m.active = true
             and (lower(m.name) like lower(concat('%', :q, '%'))
                  or lower(coalesce(m.genericName, '')) like lower(concat('%', :q, '%'))
                  or lower(coalesce(m.manufacturer, '')) like lower(concat('%', :q, '%')))
             and (:category = '' or m.category = :category)
             and exists (select i from StoreItem i where i.medicine = m and i.active = true and i.store.active = true
                         and i.store.id in :storeIds)
           """)
    Page<Medicine> searchIn(@Param("q") String q, @Param("category") String category,
                            @Param("storeIds") Collection<Long> storeIds, Pageable pageable);

    /** Listed products that some open store sells: the shop for a visitor who hasn't picked a location. */
    @Query("""
           select m from Medicine m
           where m.active = true
             and (lower(m.name) like lower(concat('%', :q, '%'))
                  or lower(coalesce(m.genericName, '')) like lower(concat('%', :q, '%'))
                  or lower(coalesce(m.manufacturer, '')) like lower(concat('%', :q, '%')))
             and (:category = '' or m.category = :category)
             and exists (select i from StoreItem i where i.medicine = m and i.active = true and i.store.active = true)
           """)
    Page<Medicine> searchSold(@Param("q") String q, @Param("category") String category, Pageable pageable);

    /** Every category a product has, listed or hidden. */
    @Query("select distinct m.category from Medicine m")
    List<String> findAllCategories();

    @Query("select distinct m.category from Medicine m where m.active = true order by m.category")
    List<String> findActiveCategories();

    @Query("""
           select distinct m.category from Medicine m
           where m.active = true and exists (select i from StoreItem i where i.medicine = m and i.active = true
                 and i.store.active = true and i.store.id in :storeIds)
           order by m.category
           """)
    List<String> categoriesIn(@Param("storeIds") Collection<Long> storeIds);

    @Query("""
           select m from Medicine m
           where m.active = true and exists (select i from StoreItem i where i.medicine = m and i.active = true
                 and i.store.active = true and i.store.id in :storeIds)
           order by m.name
           """)
    List<Medicine> soldIn(@Param("storeIds") Collection<Long> storeIds);

    @Query("""
           select m from Medicine m
           where m.active = true and exists (select i from StoreItem i where i.medicine = m and i.active = true and i.store.active = true)
           order by m.name
           """)
    List<Medicine> sold();

    List<Medicine> findByActiveTrueOrderByNameAsc();

    List<Medicine> findByProductCodeIsNull();
}
