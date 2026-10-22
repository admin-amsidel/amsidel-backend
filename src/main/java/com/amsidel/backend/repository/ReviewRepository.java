package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    @Query("select r from Review r where r.cycle.id = :cycleId and r.employee.id = :employeeId")
    Optional<Review> find(@Param("cycleId") Long cycleId, @Param("employeeId") Long employeeId);

    @Query("select r from Review r where r.cycle.id = :cycleId and r.employee.id in :employeeIds")
    List<Review> forPeople(@Param("cycleId") Long cycleId, @Param("employeeIds") Collection<Long> employeeIds);
}
