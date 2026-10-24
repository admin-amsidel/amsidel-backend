package com.amsidel.backend.repository;

import com.amsidel.backend.entity.TradeHistory;
import com.amsidel.backend.entity.TradeSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** History, newest first. */
public interface TradeHistoryRepository extends JpaRepository<TradeHistory, Long> {

    @Query("select h from TradeHistory h left join fetch h.doneBy where h.subjectType in :types and h.subjectId = :id"
            + " order by h.doneAt desc, h.id desc")
    List<TradeHistory> forSubject(@Param("types") Collection<TradeSubject> types, @Param("id") Long id);

    @Query("select h from TradeHistory h left join fetch h.doneBy where h.projectId = :projectId"
            + " or (h.subjectType = com.amsidel.backend.entity.TradeSubject.PROJECT and h.subjectId = :projectId)"
            + " order by h.doneAt desc, h.id desc")
    List<TradeHistory> forProject(@Param("projectId") Long projectId);

    @Query("select h from TradeHistory h join fetch h.brand b left join fetch h.doneBy"
            + " where (:brandId is null or b.id = :brandId) and h.doneAt >= :from and h.doneAt < :to order by h.doneAt desc, h.id desc")
    List<TradeHistory> inRange(@Param("brandId") Long brandId, @Param("from") Instant from, @Param("to") Instant to);
}
