package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Payslip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PayslipRepository extends JpaRepository<Payslip, Long> {

    @Query("select p from Payslip p join fetch p.employee e left join fetch e.designation left join fetch e.brand"
            + " left join fetch p.lines left join fetch p.publishedBy where p.id = :id")
    Optional<Payslip> findFull(@Param("id") Long id);

    /** Every payslip of one month, with lines. */
    @Query("select distinct p from Payslip p left join fetch p.lines where p.periodYear = :year and p.periodMonth = :month")
    List<Payslip> ofMonth(@Param("year") int year, @Param("month") int month);

    /** One person's payslips of a financial year (drafts too), with lines, oldest first. */
    @Query("select distinct p from Payslip p left join fetch p.lines where p.employee.id = :employeeId and p.fyStartYear = :fy"
            + " order by p.periodYear, p.periodMonth")
    List<Payslip> ofEmployeeYear(@Param("employeeId") Long employeeId, @Param("fy") int fy);

    boolean existsByEmployee_IdAndPeriodYearAndPeriodMonth(Long employeeId, int year, int month);

    /** Financial years in which this person has a published payslip. */
    @Query("select distinct p.fyStartYear from Payslip p where p.employee.id = :employeeId and p.status = 'PUBLISHED'"
            + " and p.paidOn is not null")
    List<Integer> publishedYears(@Param("employeeId") Long employeeId);

    /** Salaries credited between two days (with the lines, for the net pay). */
    @Query("select distinct p from Payslip p join fetch p.employee e left join fetch e.brand left join fetch p.lines"
            + " where p.paidOn between :from and :to")
    List<Payslip> paidBetween(@Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
}
