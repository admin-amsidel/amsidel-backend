package com.amsidel.backend.repository;

import com.amsidel.backend.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Quotations and invoices with their customer and brand. */
public interface SalesDocRepository extends JpaRepository<SalesDoc, Long> {

    @Query("select d from SalesDoc d join fetch d.brand b join fetch d.customer c"
            + " where (:brandId is null or b.id = :brandId) and d.kind = :kind and d.docDate between :from and :to"
            + " order by d.docDate desc, d.id desc")
    List<SalesDoc> list(@Param("brandId") Long brandId, @Param("kind") SalesDocKind kind,
                        @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select distinct d from SalesDoc d join fetch d.brand b join fetch d.customer left join fetch d.lines"
            + " where (:brandId is null or b.id = :brandId) and d.kind = 'INVOICE' and d.status = 'ISSUED'"
            + " and d.docDate between :from and :to order by d.docDate, d.id")
    List<SalesDoc> issued(@Param("brandId") Long brandId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select d from SalesDoc d join fetch d.brand join fetch d.customer left join fetch d.quotation"
            + " left join fetch d.createdBy left join fetch d.issuedBy where d.id = :id")
    Optional<SalesDoc> findFull(@Param("id") Long id);

    @Query("select d from SalesDoc d join fetch d.customer c where c.id = :customerId order by d.docDate desc, d.id desc")
    List<SalesDoc> forCustomer(@Param("customerId") Long customerId);

    @Query("select d from SalesDoc d where d.quotation.id = :quotationId and d.status <> 'CANCELLED'")
    List<SalesDoc> fromQuotation(@Param("quotationId") Long quotationId);

    @Query("select d from SalesDoc d join fetch d.brand b join fetch d.customer"
            + " where (:brandId is null or b.id = :brandId) and d.kind = 'INVOICE' and d.status <> 'CANCELLED'"
            + " and d.status <> 'DRAFT'")
    List<SalesDoc> openInvoices(@Param("brandId") Long brandId);
}
