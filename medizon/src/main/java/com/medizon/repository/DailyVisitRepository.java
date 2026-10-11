package com.medizon.repository;

import com.medizon.domain.DailyVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface DailyVisitRepository extends JpaRepository<DailyVisit, LocalDate> {

    /** Atomic upsert, so concurrent requests never lose a count. */
    @Modifying
    @Transactional
    @Query(value = """
            insert into daily_visits (day, visits, page_views) values (:day, :newVisit, 1)
            on conflict (day) do update set visits = daily_visits.visits + :newVisit,
                                            page_views = daily_visits.page_views + 1
            """, nativeQuery = true)
    void record(@Param("day") LocalDate day, @Param("newVisit") int newVisit);

    List<DailyVisit> findByDayGreaterThanEqualOrderByDayDesc(LocalDate from);

    @Query("select coalesce(sum(v.visits), 0) from DailyVisit v")
    long totalVisits();

    @Query("select coalesce(sum(v.visits), 0) from DailyVisit v where v.day >= :from")
    long visitsSince(@Param("from") LocalDate from);
}
