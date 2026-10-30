/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SkillRepository extends JpaRepository<Skill, Long> {

    @Query("select s from Skill s left join fetch s.validatedBy left join fetch s.addedBy where s.review.id = :reviewId"
            + " order by s.category, s.id")
    List<Skill> forReview(@Param("reviewId") Long reviewId);

    @Query("select s from Skill s join fetch s.review r join fetch r.cycle join fetch r.employee e left join fetch e.brand"
            + " left join fetch e.reportsTo where s.id = :id")
    Optional<Skill> findFull(@Param("id") Long id);
}
