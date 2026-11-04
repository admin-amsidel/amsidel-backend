/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.MailCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** The day's count of one kind of mail, per brand. */
@NoRepositoryBean
public interface MailCountRepository<D extends MailCount> extends JpaRepository<D, Long> {

    List<D> findBySentOn(LocalDate sentOn);

    boolean existsByBrandKeyAndSentOn(Long brandKey, LocalDate sentOn);

    /** Counts one email if the day's limit allows it, in one statement, so parallel sends cannot pass the limit; 0 = it does not. */
    @Transactional
    @Modifying
    @Query("update #{#entityName} c set c.sentCount = c.sentCount + 1"
            + " where c.brandKey = :brandKey and c.sentOn = :day and c.sentCount < :limit")
    int take(@Param("brandKey") Long brandKey, @Param("day") LocalDate day, @Param("limit") int limit);

    /** An email that did not go out does not count. */
    @Transactional
    @Modifying
    @Query("update #{#entityName} c set c.sentCount = c.sentCount - 1"
            + " where c.brandKey = :brandKey and c.sentOn = :day and c.sentCount > 0")
    int giveBack(@Param("brandKey") Long brandKey, @Param("day") LocalDate day);
}
