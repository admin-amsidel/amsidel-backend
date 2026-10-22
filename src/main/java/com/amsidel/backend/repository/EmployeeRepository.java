package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    @Query("select e from Employee e left join fetch e.brand left join fetch e.designation left join fetch e.reportsTo"
            + " where e.userId = :userId")
    Optional<Employee> findByUserId(@Param("userId") String userId);

    @Query("select e from Employee e left join fetch e.brand left join fetch e.designation left join fetch e.reportsTo"
            + " where e.id = :id")
    Optional<Employee> findWithBrandById(@Param("id") Long id);

    /** Everyone, or one brand's people when brandId is given. */
    @Query("select e from Employee e left join fetch e.brand b left join fetch e.designation"
            + " left join fetch e.reportsTo where (:brandId is null or b.id = :brandId) order by e.name")
    List<Employee> search(@Param("brandId") Long brandId);

    /** Active people who report directly to this person, most senior first. */
    @Query("select e from Employee e left join fetch e.brand left join fetch e.designation d"
            + " where e.reportsTo.id = :id and e.active = true order by d.levelNumber desc nulls last, e.name")
    List<Employee> directReports(@Param("id") Long id);

    boolean existsByUserId(String userId);

    boolean existsByEmpNumberIgnoreCase(String empNumber);

    /** Highest employee number with this prefix, used to generate the next one. */
    Optional<Employee> findTopByEmpNumberStartingWithOrderByEmpNumberDesc(String prefix);
}
