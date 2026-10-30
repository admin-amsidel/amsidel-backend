/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/**
 * How a goal is evaluated. GENERAL: weighted sub-tasks with % done moved by hand, rated in stars by the person.
 * SALES: a target amount (split over the sub-tasks); the person records what they achieved (amount, dates, invoice
 * numbers), and the progress and self rating follow achieved / target on their own.
 */
public enum GoalType {
    GENERAL,
    SALES
}
