/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.StorageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface StorageSnapshotRepository extends JpaRepository<StorageSnapshot, Long> {

    Optional<StorageSnapshot> findTopByOrderByMeasuredAtDescIdDesc();

    /** Old ones go; a few days are kept to compare. */
    @Modifying
    @Query("delete from StorageSnapshot s where s.measuredAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
