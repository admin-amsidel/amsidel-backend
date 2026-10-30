/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.MoneyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Kinds of other income and expense. */
public interface MoneyTypeRepository extends JpaRepository<MoneyType, String> {

    List<MoneyType> findAllByOrderByIncomeDescSortOrderAscLabelAsc();

    boolean existsByLabelIgnoreCaseAndIncome(String label, boolean income);
}
