/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** One type of leave in a plan; a type with no line is not available to that brand in that period. */
@Entity
@Table(name = "leave_plan_lines")
@Getter
@Setter
@NoArgsConstructor
public class LeavePlanLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private LeavePlan plan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    /** Days everyone gets in the period (types with days every year); null otherwise. */
    @Column(name = "days")
    private Integer days;

    /** What is left of this type when the plan ends goes on to the brand's next plan. */
    @Column(name = "carry_forward", nullable = false)
    private boolean carryForward;

    /** At most this many days go on; null = all that is left. */
    @Column(name = "max_carry_days", precision = 5, scale = 1)
    private BigDecimal maxCarryDays;
}
