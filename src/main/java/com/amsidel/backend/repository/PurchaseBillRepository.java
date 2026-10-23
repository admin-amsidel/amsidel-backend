package com.amsidel.backend.repository;

import com.amsidel.backend.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Purchase bills with their supplier and brand. */
public interface PurchaseBillRepository extends JpaRepository<PurchaseBill, Long> {

    @Query("select p from PurchaseBill p join fetch p.brand b join fetch p.supplier"
            + " where (:brandId is null or b.id = :brandId) and p.billDate between :from and :to"
            + " order by p.billDate desc, p.id desc")
    List<PurchaseBill> list(@Param("brandId") Long brandId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select distinct p from PurchaseBill p join fetch p.brand b join fetch p.supplier left join fetch p.lines"
            + " where (:brandId is null or b.id = :brandId) and p.status = 'RECEIVED'"
            + " and p.billDate between :from and :to order by p.billDate, p.id")
    List<PurchaseBill> received(@Param("brandId") Long brandId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select p from PurchaseBill p join fetch p.brand join fetch p.supplier left join fetch p.createdBy"
            + " left join fetch p.receivedBy where p.id = :id")
    Optional<PurchaseBill> findFull(@Param("id") Long id);

    @Query("select p from PurchaseBill p join fetch p.supplier s where s.id = :supplierId order by p.billDate desc, p.id desc")
    List<PurchaseBill> forSupplier(@Param("supplierId") Long supplierId);

    @Query("select p from PurchaseBill p join fetch p.brand b join fetch p.supplier"
            + " where (:brandId is null or b.id = :brandId) and p.status = 'RECEIVED'")
    List<PurchaseBill> openBills(@Param("brandId") Long brandId);
}
