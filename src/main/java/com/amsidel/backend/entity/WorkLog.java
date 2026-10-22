package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One task worked on one day: the assigned task ID, hours spent and what was done. Several per day. */
@Entity
@Table(name = "work_logs", indexes = @Index(name = "ix_work_logs_employee_date", columnList = "employee_id, work_date"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class WorkLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** Order within the day (1, 2, 3 ...), in the order the entries were added. */
    @Column(nullable = false)
    private int sequence;

    /** Assigned task's code, e.g. MSC-1024 (kept as text so history reads the same if a task is renamed). */
    @Column(nullable = false, length = 50)
    private String taskId;

    /** The task itself; null only for entries logged before tasks were managed in the portal. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_ref_id")
    private WorkTask task;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal hours;

    @Column(nullable = false, length = 1000)
    private String description;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
