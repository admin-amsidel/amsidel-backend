/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.WorkHourRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkHourRuleRepository extends JpaRepository<WorkHourRule, Long> {

    List<WorkHourRule> findAllByOrderByLevelNumberAsc();

    Optional<WorkHourRule> findByLevelNumber(int levelNumber);
}
