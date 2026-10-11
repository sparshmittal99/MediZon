package com.medizon.repository;

import com.medizon.domain.Order;
import com.medizon.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByOrder(Order order);
    boolean existsByOrder(Order order);

    @Query("select r from Review r join fetch r.user join fetch r.order order by r.createdAt desc")
    List<Review> findAllNewestFirst();

    @Query("select r from Review r join fetch r.user join fetch r.order where r.rating = :rating order by r.createdAt desc")
    List<Review> findByRatingNewestFirst(@Param("rating") int rating);

    @Query("select avg(r.rating) from Review r")
    Double averageRating();

    /** Rows of [rating, count]. */
    @Query("select r.rating, count(r) from Review r group by r.rating")
    List<Object[]> countByRating();
}
