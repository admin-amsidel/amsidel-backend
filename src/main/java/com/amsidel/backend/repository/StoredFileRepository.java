/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.StoredFile;
import com.amsidel.backend.entity.StoredFileCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long>, JpaSpecificationExecutor<StoredFile> {

    Optional<StoredFile> findByCategoryAndRefId(StoredFileCategory category, Long refId);

    @Modifying
    @Query("delete from StoredFile s where s.category = :category and s.refId = :refId")
    void forget(@Param("category") StoredFileCategory category, @Param("refId") Long refId);

    @Override
    @EntityGraph(attributePaths = {"brand", "uploadedBy"})
    Page<StoredFile> findAll(Specification<StoredFile> spec, Pageable pageable);

    /** {category, files, bytes}. */
    @Query("select s.category, count(s), coalesce(sum(s.sizeBytes), 0) from StoredFile s where (:brandId is null or s.brand.id = :brandId) group by s.category")
    List<Object[]> byCategory(@Param("brandId") Long brandId);

    /** {file type, files, bytes}. */
    @Query("select s.fileType, count(s), coalesce(sum(s.sizeBytes), 0) from StoredFile s where (:brandId is null or s.brand.id = :brandId) group by s.fileType")
    List<Object[]> byFileType(@Param("brandId") Long brandId);

    /** {brand code or null, files, bytes}. */
    @Query("select b.code, count(s), coalesce(sum(s.sizeBytes), 0) from StoredFile s left join s.brand b where (:brandId is null or b.id = :brandId) group by b.code")
    List<Object[]> byBrand(@Param("brandId") Long brandId);

    /** {location, files, bytes}. */
    @Query("select s.location, count(s), coalesce(sum(s.sizeBytes), 0) from StoredFile s group by s.location")
    List<Object[]> byLocation();

    /** {files, bytes} uploaded since then. */
    @Query("select count(s), coalesce(sum(s.sizeBytes), 0) from StoredFile s where s.uploadedAt >= :since and (:brandId is null or s.brand.id = :brandId)")
    List<Object[]> since(@Param("since") Instant since, @Param("brandId") Long brandId);
}
