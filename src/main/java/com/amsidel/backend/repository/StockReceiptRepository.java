/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.StockReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockReceiptRepository extends JpaRepository<StockReceipt, Long> {

    /** Newest first; brandId null = every brand. */
    @Query("select distinct r from StockReceipt r join fetch r.brand b left join fetch r.lines left join fetch r.createdBy"
            + " left join fetch r.decidedBy where (:brandId is null or b.id = :brandId) order by r.createdAt desc")
    List<StockReceipt> forBrand(@Param("brandId") Long brandId);

    @Query("select r from StockReceipt r join fetch r.brand left join fetch r.lines where r.id = :id")
    Optional<StockReceipt> findFull(@Param("id") Long id);
}
