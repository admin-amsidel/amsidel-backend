package com.amsidel.backend.repository;

import com.amsidel.backend.entity.PaymentSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Where online payments came from, newest first. */
public interface PaymentSourceRepository extends JpaRepository<PaymentSource, Long> {

    @Query("select s from PaymentSource s join fetch s.brand b join fetch s.customer join fetch s.onlinePayment"
            + " where (:brandId is null or b.id = :brandId) and s.createdAt >= :from and s.createdAt < :to order by s.createdAt desc, s.id desc")
    List<PaymentSource> inRange(@Param("brandId") Long brandId, @Param("from") Instant from, @Param("to") Instant to);

    Optional<PaymentSource> findByOnlinePaymentId(Long onlinePaymentId);

    Optional<PaymentSource> findByTransactionIdIgnoreCase(String transactionId);

    List<PaymentSource> findByOnlinePaymentIdIn(java.util.Collection<Long> onlinePaymentIds);
}
