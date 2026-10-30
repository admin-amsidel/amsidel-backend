/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.BankStatement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Uploaded bank statements, newest first. */
public interface BankStatementRepository extends JpaRepository<BankStatement, Long> {

    @Query("select s from BankStatement s join fetch s.brand b left join fetch s.uploadedBy"
            + " where (:brandId is null or b.id = :brandId) order by s.fromDate desc, s.id desc")
    List<BankStatement> forBrand(@Param("brandId") Long brandId);

    @Query("select s from BankStatement s where s.brand.id = :brandId and s.fromDate <= :to and s.toDate >= :from")
    List<BankStatement> overlapping(@Param("brandId") Long brandId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select s from BankStatement s join fetch s.brand left join fetch s.uploadedBy where s.id = :id")
    Optional<BankStatement> findFull(@Param("id") Long id);
}
