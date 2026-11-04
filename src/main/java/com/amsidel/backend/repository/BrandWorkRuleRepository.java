/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.BrandWorkRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BrandWorkRuleRepository extends JpaRepository<BrandWorkRule, Long> {

    Optional<BrandWorkRule> findByBrandKeyAndDesignationKey(Long brandKey, Long designationKey);
}
