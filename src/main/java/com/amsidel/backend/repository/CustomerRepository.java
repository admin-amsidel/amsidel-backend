package com.amsidel.backend.repository;

import com.amsidel.backend.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Customers; brandId null = every brand. */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    @Query("select c from Customer c join fetch c.brand b where (:brandId is null or b.id = :brandId) order by c.name")
    List<Customer> forBrand(@Param("brandId") Long brandId);

    @Query("select c from Customer c join fetch c.brand where c.id = :id")
    Optional<Customer> findFull(@Param("id") Long id);

    Optional<Customer> findByBrandIdAndPhone(Long brandId, String phone);
}
