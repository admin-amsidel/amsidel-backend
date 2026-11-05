/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    /** Every agent, or one brand's. */
    @Query("select a from Agent a join fetch a.brand b where (:brandId is null or b.id = :brandId) order by a.name")
    List<Agent> forBrand(@Param("brandId") Long brandId);
}
