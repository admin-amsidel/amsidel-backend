/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.NotificationClear;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface NotificationClearRepository extends JpaRepository<NotificationClear, Long> {

    List<NotificationClear> findByEmployeeId(Long employeeId);

    boolean existsByEmployeeIdAndMeetingIdAndKind(Long employeeId, Long meetingId, NotificationClear.Kind kind);

    /** The meeting changed or went: its marks go with it. */
    @Modifying
    @Transactional
    @Query("delete from NotificationClear c where c.meetingId = :meetingId")
    void deleteForMeeting(@Param("meetingId") Long meetingId);

    /** Marks for meetings too old to notify about. */
    @Modifying
    @Transactional
    @Query("delete from NotificationClear c where c.employeeId = :employeeId and c.meetingDate < :before")
    void deleteOld(@Param("employeeId") Long employeeId, @Param("before") LocalDate before);
}
