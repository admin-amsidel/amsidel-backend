/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** Stock changes, newest first. */
public interface StockMoveRepository extends JpaRepository<StockMove, Long> {

    @Query("select m from StockMove m left join fetch m.purchaseBill left join fetch m.salesDoc left join fetch m.recordedBy"
            + " where m.item.id = :itemId order by m.movedOn desc, m.id desc")
    List<StockMove> forItem(@Param("itemId") Long itemId);

    @Query("select m from StockMove m join fetch m.item i join fetch i.brand b"
            + " where (:brandId is null or b.id = :brandId) and m.movedOn between :from and :to order by m.movedOn, m.id")
    List<StockMove> inRange(@Param("brandId") Long brandId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
