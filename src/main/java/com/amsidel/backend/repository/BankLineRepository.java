package com.amsidel.backend.repository;

import com.amsidel.backend.entity.BankLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** Bank statement lines, in the bank's order. */
public interface BankLineRepository extends JpaRepository<BankLine, Long> {

    @Query("select l from BankLine l join fetch l.statement s where l.brand.id = :brandId and l.txnDate between :from and :to"
            + " order by l.txnDate, s.fromDate, l.lineNo")
    List<BankLine> inRange(@Param("brandId") Long brandId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Lines with a balance up to a day: the last one is the balance that day. */
    @Query("select l from BankLine l join fetch l.statement s where l.brand.id = :brandId and l.balance is not null"
            + " and l.txnDate <= :day order by l.txnDate desc, s.fromDate desc, l.lineNo desc")
    List<BankLine> balancesUpTo(@Param("brandId") Long brandId, @Param("day") LocalDate day,
                                org.springframework.data.domain.Pageable page);
}
