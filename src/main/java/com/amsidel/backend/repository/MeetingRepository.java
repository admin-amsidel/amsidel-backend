/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    /** Meetings I added or am in, with their people, in time order. */
    @Query("select distinct m from Meeting m join fetch m.createdBy left join fetch m.people"
            + " where m.meetingDate between :from and :to"
            + " and (m.createdBy.id = :employeeId or exists (select 1 from Meeting x join x.people p where x.id = m.id and p.id = :employeeId))"
            + " order by m.meetingDate, m.startTime, m.id")
    List<Meeting> forEmployee(@Param("employeeId") Long employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Meetings with any of these people in them, with all their people, in time order. */
    @Query("select distinct m from Meeting m join fetch m.createdBy left join fetch m.people"
            + " where m.meetingDate between :from and :to"
            + " and exists (select 1 from Meeting x join x.people p where x.id = m.id and p.id in :ids)"
            + " order by m.meetingDate, m.startTime, m.id")
    List<Meeting> ofPeople(@Param("ids") java.util.Collection<Long> ids, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select m from Meeting m join fetch m.createdBy left join fetch m.people where m.id = :id")
    Optional<Meeting> findFull(@Param("id") Long id);
}
