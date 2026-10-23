package com.amsidel.backend.repository;

import com.amsidel.backend.entity.PayComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PayComponentRepository extends JpaRepository<PayComponent, Long> {

    /** Earnings, then deductions, then reimbursements; in their order within each. */
    default List<PayComponent> ordered() {
        return findAll().stream()
                .sorted(java.util.Comparator.comparing((PayComponent c) -> c.getKind().ordinal())
                        .thenComparingInt(PayComponent::getSortOrder).thenComparing(PayComponent::getName))
                .toList();
    }

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    /** Renames a component that still has its old name, on the component and on the payslips made with it. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("update PayComponent c set c.name = :name where upper(c.code) = upper(:code) and c.name = :old")
    int rename(@Param("code") String code, @Param("old") String old, @Param("name") String name);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("update PayslipLine l set l.name = :name where upper(l.code) = upper(:code) and l.name = :old")
    int renameOnPayslips(@Param("code") String code, @Param("old") String old, @Param("name") String name);

    /** Is it used by any salary? */
    @Query("select count(l) > 0 from SalaryLine l where l.component.id = :id")
    boolean inUse(@Param("id") Long id);
}
