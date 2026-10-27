package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    @Query("select e from Employee e left join fetch e.brand left join fetch e.designation left join fetch e.reportsTo"
            + " where e.userId = :userId")
    Optional<Employee> findByUserId(@Param("userId") String userId);

    @Query("select e from Employee e left join fetch e.brand left join fetch e.designation left join fetch e.reportsTo"
            + " left join fetch e.userType where e.id = :id")
    Optional<Employee> findWithBrandById(@Param("id") Long id);

    /** Everyone, or one brand's people when brandId is given. */
    @Query("select e from Employee e left join fetch e.brand b left join fetch e.designation"
            + " left join fetch e.reportsTo where (:brandId is null or b.id = :brandId) order by e.name")
    List<Employee> search(@Param("brandId") Long brandId);

    /** Active people who report directly to this person, most senior first. */
    @Query("select e from Employee e left join fetch e.brand left join fetch e.designation d"
            + " where e.reportsTo.id = :id and e.active = true order by d.levelNumber desc nulls last, e.name")
    List<Employee> directReports(@Param("id") Long id);

    /** Ids of the person and everyone above them, in one round trip (UNION stops if the data ever loops). */
    @Query(value = "with recursive up(id, reports_to_id) as ("
            + " select id, reports_to_id from employees where id = :id"
            + " union select e.id, e.reports_to_id from employees e join up on e.id = up.reports_to_id"
            + ") select id from up", nativeQuery = true)
    List<Long> lineIds(@Param("id") Long id);

    /** These people with their brand and designation, for charts. */
    @Query("select e from Employee e left join fetch e.brand left join fetch e.designation where e.id in :ids")
    List<Employee> withDetails(@Param("ids") Collection<Long> ids);

    boolean existsByUserId(String userId);

    long countByUserType_Id(Long userTypeId);

    long countByDesignation_Id(Long designationId);

    List<Employee> findByUserType_Id(Long userTypeId);

    /** [userTypeId, count] for the types in use. */
    @Query("select e.userType.id, count(e) from Employee e where e.userType is not null group by e.userType.id")
    List<Object[]> countByUserTypeRows();

    /** [role, count] for people with no user type yet (added before user types): theirs is the built-in one of the role. */
    @Query("select e.role, count(e) from Employee e where e.userType is null group by e.role")
    List<Object[]> countWithoutUserTypeByRole();

    default java.util.Map<Long, Long> countByUserType() {
        java.util.Map<Long, Long> out = new java.util.HashMap<>();
        for (Object[] r : countByUserTypeRows()) {
            out.put((Long) r[0], ((Number) r[1]).longValue());
        }
        return out;
    }

    boolean existsByEmpNumberIgnoreCase(String empNumber);

    /** Highest employee number with this prefix, used to generate the next one. */
    Optional<Employee> findTopByEmpNumberStartingWithOrderByEmpNumberDesc(String prefix);

    /** Does anyone active report to this person? */
    boolean existsByReportsTo_IdAndActiveTrue(Long employeeId);

    /** Active people with a date of birth (for the birthdays on the dashboard). */
    @Query("select e from Employee e left join fetch e.designation left join fetch e.brand where e.active = true and e.dob is not null")
    List<Employee> withBirthdays();
}
