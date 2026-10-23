package com.amsidel.backend.repository;

import com.amsidel.backend.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Payments in and out, newest first. */
public interface TradePaymentRepository extends JpaRepository<TradePayment, Long> {

    @Query("select p from TradePayment p left join fetch p.recordedBy left join fetch p.voidedBy"
            + " where p.invoice.id = :docId order by p.paidOn, p.id")
    List<TradePayment> forInvoice(@Param("docId") Long docId);

    @Query("select p from TradePayment p left join fetch p.recordedBy left join fetch p.voidedBy"
            + " where p.purchaseBill.id = :billId order by p.paidOn, p.id")
    List<TradePayment> forBill(@Param("billId") Long billId);

    @Query("select p from TradePayment p join fetch p.brand b left join fetch p.customer left join fetch p.supplier"
            + " left join fetch p.invoice left join fetch p.purchaseBill left join fetch p.recordedBy"
            + " where (:brandId is null or b.id = :brandId) and p.paidOn between :from and :to order by p.paidOn, p.id")
    List<TradePayment> inRange(@Param("brandId") Long brandId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select p from TradePayment p join fetch p.brand left join fetch p.invoice left join fetch p.purchaseBill"
            + " where p.id = :id")
    Optional<TradePayment> findFull(@Param("id") Long id);
}
