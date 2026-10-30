/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Suppliers; brandId null = every brand. */
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    @Query("select s from Supplier s join fetch s.brand b where (:brandId is null or b.id = :brandId) order by s.name")
    List<Supplier> forBrand(@Param("brandId") Long brandId);

    @Query("select s from Supplier s join fetch s.brand where s.id = :id")
    Optional<Supplier> findFull(@Param("id") Long id);

    boolean existsByBrandIdAndCodeIgnoreCase(Long brandId, String code);

    boolean existsByBrandIdAndCodeIgnoreCaseAndIdNot(Long brandId, String code, Long id);
}
