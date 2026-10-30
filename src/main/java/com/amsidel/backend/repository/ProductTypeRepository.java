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

/** Product types; brandId null = every brand. */
public interface ProductTypeRepository extends JpaRepository<ProductType, Long> {

    @Query("select t from ProductType t join fetch t.brand b where (:brandId is null or b.id = :brandId)"
            + " order by b.code, t.sortOrder, t.name")
    List<ProductType> forBrand(@Param("brandId") Long brandId);

    boolean existsByBrandIdAndCodeIgnoreCase(Long brandId, String code);

    boolean existsByBrandIdAndCodeIgnoreCaseAndIdNot(Long brandId, String code, Long id);

    long countByBrandId(Long brandId);

    Optional<ProductType> findFirstByBrandIdAndCodeIgnoreCase(Long brandId, String code);
}
