package com.amsidel.backend.repository;

import com.amsidel.backend.entity.OnlinePayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Online payments, newest first. */
public interface OnlinePaymentRepository extends JpaRepository<OnlinePayment, Long> {

    @Query("select o from OnlinePayment o join fetch o.brand join fetch o.customer join fetch o.invoice left join fetch o.requestedBy"
            + " left join fetch o.tradePayment where o.id = :id")
    Optional<OnlinePayment> findFull(@Param("id") Long id);

    @Query("select o from OnlinePayment o join fetch o.brand b join fetch o.customer join fetch o.invoice left join fetch o.requestedBy"
            + " where (:brandId is null or b.id = :brandId) and o.createdAt >= :from and o.createdAt < :to order by o.createdAt desc, o.id desc")
    List<OnlinePayment> inRange(@Param("brandId") Long brandId, @Param("from") Instant from, @Param("to") Instant to);
}
