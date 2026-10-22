package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    /** The day's entries in order. */
    @Query("select a from Attendance a where a.employee.id = :employeeId and a.workDate = :day order by a.entryNo")
    List<Attendance> findDay(@Param("employeeId") Long employeeId, @Param("day") LocalDate day);

    /** Newest day first, each day's entries in order. */
    @Query("select a from Attendance a where a.employee.id = :employeeId and a.workDate between :from and :to"
            + " order by a.workDate desc, a.entryNo")
    List<Attendance> forEmployee(@Param("employeeId") Long employeeId, @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    @Query("select a from Attendance a where a.workDate = :day and a.employee.id in :employeeIds order by a.entryNo")
    List<Attendance> onDay(@Param("day") LocalDate day, @Param("employeeIds") Collection<Long> employeeIds);

    @Query("select a from Attendance a join fetch a.employee e left join fetch e.brand left join fetch e.reportsTo"
            + " where a.id = :id")
    Optional<Attendance> findWithEmployee(@Param("id") Long id);
}
