package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Goal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    @Query("select distinct g from Goal g left join fetch g.tasks left join fetch g.managerRatedBy"
            + " where g.review.id = :reviewId order by g.startDate, g.id")
    List<Goal> forReview(@Param("reviewId") Long reviewId);

    @Query("select distinct g from Goal g left join fetch g.tasks where g.review.id in :reviewIds")
    List<Goal> forReviews(@Param("reviewIds") Collection<Long> reviewIds);

    @Query("select g from Goal g join fetch g.review r join fetch r.cycle join fetch r.employee e left join fetch e.brand"
            + " left join fetch e.reportsTo where g.id = :id")
    Optional<Goal> findFull(@Param("id") Long id);

    @Query("select coalesce(sum(g.weightage), 0) from Goal g where g.review.id = :reviewId and (:exceptId is null or g.id <> :exceptId)")
    int weightOf(@Param("reviewId") Long reviewId, @Param("exceptId") Long exceptId);
}
