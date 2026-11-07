/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What a Manual Sales worklog task is about, typed by hand. Kept as text and apart from the customers, projects and agents
 * of the portals: this flow is for brands without the product portal, so nothing here points at their records.
 */
@Entity
@Table(name = "manual_sales_tasks")
@Getter
@Setter
@NoArgsConstructor
public class ManualSalesTask {

    /** Same value as tasks.id. */
    @Id
    @Column(name = "task_id")
    private Long taskId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "task_id")
    private WorkTask task;

    @Column(length = 150)
    private String customerName;

    @Column(length = 30)
    private String customerPhone;

    @Column(length = 150)
    private String projectName;

    @Column(length = 150)
    private String agentName;

    @Column(length = 30)
    private String agentPhone;
}
