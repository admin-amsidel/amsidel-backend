/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Designation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DesignationRepository extends JpaRepository<Designation, Long> {

    /** Most junior first: level 1 Trainee ... level 10 CEO. */
    List<Designation> findAllByOrderByLevelNumberAscLevelCodeAsc();

    /** With their brands: group-wide ones first, then by brand, each most junior first. */
    @Query("select d from Designation d left join fetch d.brand b order by case when b.id is null then 0 else 1 end, b.name,"
            + " d.levelNumber, d.levelCode")
    List<Designation> listWithBrands();

    /** Another designation of the same ladder (brandId null = the group-wide ones) with this name. */
    @Query("select count(d) > 0 from Designation d left join d.brand b where lower(d.name) = lower(:name)"
            + " and ((:brandId is null and b.id is null) or b.id = :brandId) and d.id <> :exceptId")
    boolean nameTaken(@Param("name") String name, @Param("brandId") Long brandId, @Param("exceptId") Long exceptId);

    /** Another designation of the same ladder with this short code. */
    @Query("select count(d) > 0 from Designation d left join d.brand b where lower(d.shortCode) = lower(:code)"
            + " and ((:brandId is null and b.id is null) or b.id = :brandId) and d.id <> :exceptId")
    boolean shortCodeTaken(@Param("code") String code, @Param("brandId") Long brandId, @Param("exceptId") Long exceptId);

    /** Another designation of the same ladder with this level code. */
    @Query("select count(d) > 0 from Designation d left join d.brand b where d.levelCode = :code"
            + " and ((:brandId is null and b.id is null) or b.id = :brandId) and d.id <> :exceptId")
    boolean levelTaken(@Param("code") String code, @Param("brandId") Long brandId, @Param("exceptId") Long exceptId);
}
