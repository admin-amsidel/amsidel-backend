package com.amsidel.backend.repository;

import com.amsidel.backend.entity.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Items with their type and usual supplier; brandId null = every brand. */
public interface ProductItemRepository extends JpaRepository<ProductItem, Long> {

    @Query("select i from ProductItem i join fetch i.brand b join fetch i.type left join fetch i.supplier"
            + " where (:brandId is null or b.id = :brandId) order by i.name")
    List<ProductItem> forBrand(@Param("brandId") Long brandId);

    @Query("select i from ProductItem i join fetch i.brand join fetch i.type left join fetch i.supplier where i.id = :id")
    Optional<ProductItem> findFull(@Param("id") Long id);

    /** For a stock change: one at a time per item. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ProductItem i where i.id = :id")
    Optional<ProductItem> lockById(@Param("id") Long id);

    boolean existsByBrandIdAndCodeIgnoreCase(Long brandId, String code);

    boolean existsByBrandIdAndCodeIgnoreCaseAndIdNot(Long brandId, String code, Long id);

    boolean existsByTypeId(Long typeId);
}
