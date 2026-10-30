/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.ProjectStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectStageRepository extends JpaRepository<ProjectStage, String> {

    List<ProjectStage> findAllByOrderBySortOrderAscNameAsc();
}
