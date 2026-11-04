/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.OfferLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OfferLetterRepository extends JpaRepository<OfferLetter, Long> {

    @Query("select o from OfferLetter o join fetch o.brand order by o.id desc")
    List<OfferLetter> listWithBrands();

    /** The last number given this year in this brand; 0 for none. */
    @Query("select coalesce(max(o.letterSeq), 0) from OfferLetter o where o.brand.id = :brandId and o.letterYear = :year")
    int lastSeq(@Param("brandId") Long brandId, @Param("year") int year);

    /** The latest issued letter of the brand: a new letter starts from its introduction and clauses. */
    Optional<OfferLetter> findFirstByBrandIdAndStatusOrderByIdDesc(Long brandId, OfferLetter.Status status);
}
