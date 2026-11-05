/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.AgentCommission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentCommissionRepository extends JpaRepository<AgentCommission, Long> {

    /** Every commission, or one brand's, newest first, with what the list shows. */
    @Query("select c from AgentCommission c join fetch c.agent a join fetch c.project p join fetch p.brand b join fetch p.customer"
            + " left join fetch c.statusChangedBy where (:brandId is null or b.id = :brandId) order by c.completedOn desc, c.id desc")
    List<AgentCommission> forBrand(@Param("brandId") Long brandId);

    Optional<AgentCommission> findByProject_Id(Long projectId);
}
