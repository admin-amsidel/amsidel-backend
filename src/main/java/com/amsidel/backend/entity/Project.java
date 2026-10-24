package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A job for a customer (e.g. all the windows of a new house): who owns it and works on it, its dates and money, and
 * its tasks. Quotations and invoices can belong to it, so its bills and payments are followed in one place. Once
 * settled (closed with the money done), only admins and owners may change it.
 */
@Entity
@Table(name = "projects")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    /** DWPRJ-102026-001. */
    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** The whole job: site, scope, specifications. */
    @Column(length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ProjectStatus status = ProjectStatus.QUEUED;

    /** Closed with the money done: only admins and owners may change it now. */
    @Column(nullable = false)
    private boolean settled;

    private Instant settledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settled_by")
    private Employee settledBy;

    /** Who answers for the project. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Employee owner;

    /** The people working on it. */
    @ManyToMany
    @JoinTable(name = "project_members", joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "employee_id"))
    @OrderBy("name")
    private Set<Employee> members = new LinkedHashSet<>();

    private LocalDate startDate;

    private LocalDate endDate;

    /** The amount expected at the start. */
    @Column(precision = 14, scale = 2)
    private BigDecimal estimatedAmount;

    /** The amount agreed in the end (what is to be paid). */
    @Column(precision = 14, scale = 2)
    private BigDecimal finalAmount;

    @Column(length = 2000)
    private String remarks;

    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Employee createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Employee updatedBy;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
