package com.amsidel.backend.repository;

import com.amsidel.backend.entity.BankBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Bank balances typed in, newest first. */
public interface BankBalanceRepository extends JpaRepository<BankBalance, Long> {

    @Query("select x from BankBalance x where x.brand.id = :brandId and x.asOf between :from and :to order by x.asOf desc, x.id desc")
    List<BankBalance> inMonth(@Param("brandId") Long brandId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select x from BankBalance x join fetch x.brand b left join fetch x.enteredBy left join fetch x.updatedBy"
            + " where (:brandId is null or b.id = :brandId) order by x.asOf desc")
    List<BankBalance> forBrand(@Param("brandId") Long brandId);

    @Query("select x from BankBalance x where x.brand.id = :brandId and x.asOf <= :day order by x.asOf desc")
    List<BankBalance> upTo(@Param("brandId") Long brandId, @Param("day") LocalDate day, org.springframework.data.domain.Pageable page);

    Optional<BankBalance> findByBrandIdAndAsOf(Long brandId, LocalDate asOf);
}
