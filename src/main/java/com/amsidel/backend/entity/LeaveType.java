/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/** A kind of leave and its yearly allowance, e.g. CL = Casual Leave, 10 days a year. Same for every brand. */
@Entity
@Table(name = "leave_types")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class LeaveType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveAllocation allocation;

    /** Days per calendar year; used only when allocation = ANNUAL. */
    private Integer annualQuota;

    @Column(nullable = false)
    private boolean halfDayAllowed;

    /** Only on dates in the restricted holiday list, one day per request (Restricted Holiday). */
    @Column(nullable = false)
    private boolean restrictedDatesOnly;

    @Column(nullable = false)
    private boolean active = true;

    /** Order on screens. */
    @Column(nullable = false)
    private int sortOrder;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
