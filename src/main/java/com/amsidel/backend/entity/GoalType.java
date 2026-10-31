/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/**
 * How a goal is evaluated. GENERAL: weighted sub-tasks with % done moved by hand, rated in stars by the person.
 * SALES: a target amount (split over the sub-tasks); the person records what they achieved (amount, dates, invoice
 * numbers), and the progress and self rating follow achieved / target on their own. SALES_ORDER: a target amount;
 * the sales orders confirmed in the goal's dates on projects the person is an allocated employee of count on their own.
 */
public enum GoalType {
    GENERAL,
    SALES,
    /** A target amount reached by the confirmed sales orders of the projects the person is allocated to (each order a line, by month). */
    SALES_ORDER
}
