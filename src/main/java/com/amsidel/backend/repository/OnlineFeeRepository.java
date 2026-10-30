/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.OnlineFee;
import com.amsidel.backend.entity.OnlineMethod;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnlineFeeRepository extends JpaRepository<OnlineFee, OnlineMethod> {
}
