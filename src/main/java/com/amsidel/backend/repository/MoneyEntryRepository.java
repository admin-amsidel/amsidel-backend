package com.amsidel.backend.repository;

import com.amsidel.backend.entity.MoneyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Other income and expenses, by date. */
public interface MoneyEntryRepository extends JpaRepository<MoneyEntry, Long> {

    @Query("select e from MoneyEntry e join fetch e.brand b left join fetch e.invoice left join fetch e.project"
            + " left join fetch e.customer left join fetch e.purchaseBill left join fetch e.supplier left join fetch e.createdBy"
            + " left join fetch e.updatedBy where (:brandId is null or b.id = :brandId) and e.entryDate between :from and :to"
            + " order by e.entryDate desc, e.id desc")
    List<MoneyEntry> inRange(@Param("brandId") Long brandId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select e from MoneyEntry e join fetch e.brand left join fetch e.invoice left join fetch e.project"
            + " left join fetch e.customer left join fetch e.purchaseBill left join fetch e.supplier left join fetch e.createdBy"
            + " left join fetch e.updatedBy left join fetch e.bankLine where e.id = :id")
    Optional<MoneyEntry> findFull(@Param("id") Long id);

    @Query("select e from MoneyEntry e where e.invoice.id = :docId order by e.entryDate, e.id")
    List<MoneyEntry> forInvoice(@Param("docId") Long docId);

    @Query("select e from MoneyEntry e join fetch e.brand where e.project.id = :projectId order by e.entryDate, e.id")
    List<MoneyEntry> forProject(@Param("projectId") Long projectId);

    @Query("select count(e) > 0 from MoneyEntry e where e.category.code = :code")
    boolean usesCategory(@Param("code") String code);

    @Query("select e from MoneyEntry e where e.bankLine.id in :lineIds")
    List<MoneyEntry> forBankLines(@Param("lineIds") List<Long> lineIds);
}
