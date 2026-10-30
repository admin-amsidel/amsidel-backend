/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.NewsPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** News, pinned first, then newest. */
public interface NewsPostRepository extends JpaRepository<NewsPost, Long> {

    @Query("select n from NewsPost n left join fetch n.brand left join fetch n.createdBy left join fetch n.updatedBy"
            + " order by n.pinned desc, n.startDate desc, n.id desc")
    List<NewsPost> all();

    @Query("select n from NewsPost n left join fetch n.brand where n.active = true and n.startDate <= :day"
            + " and (n.endDate is null or n.endDate >= :day) order by n.pinned desc, n.startDate desc, n.id desc")
    List<NewsPost> live(@Param("day") LocalDate day);
}
