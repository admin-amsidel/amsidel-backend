/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.UserType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserTypeRepository extends JpaRepository<UserType, Long> {

    Optional<UserType> findByCode(String code);

    boolean existsByCode(String code);

    /** Another type of the same brand (brandId null = the ones for every brand) with this name. */
    @org.springframework.data.jpa.repository.Query("select count(t) > 0 from UserType t left join t.brand b where lower(t.name) = lower(:name)"
            + " and ((:brandId is null and b.id is null) or b.id = :brandId) and t.id <> :exceptId")
    boolean nameTaken(@org.springframework.data.repository.query.Param("name") String name,
                      @org.springframework.data.repository.query.Param("brandId") Long brandId,
                      @org.springframework.data.repository.query.Param("exceptId") Long exceptId);

    /** With their brands: built-in first, then those for every brand, then each brand's, by name. */
    @org.springframework.data.jpa.repository.Query("select t from UserType t left join fetch t.brand b order by t.builtIn desc,"
            + " case when b.id is null then 0 else 1 end, b.name, t.name")
    List<UserType> listWithBrands();

    List<UserType> findAllByOrderByBuiltInDescNameAsc();
}
