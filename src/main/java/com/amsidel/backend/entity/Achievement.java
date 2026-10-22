package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** An achievement (e.g. Team Champ): issued by a manager, or added by the person themselves (issuedBy null). */
@Entity
@Table(name = "achievements", indexes = @Index(name = "ix_achievement_employee", columnList = "employee_id"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Achievement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(nullable = false, length = 120)
    private String title;

    /** e.g. Team Champ, Customer Star, Best Sales. */
    @Column(length = 60)
    private String category;

    @Column(name = "achieved_on", nullable = false)
    private LocalDate achievedOn;

    @Column(length = 2000)
    private String details;

    /** Null: added by the person themselves. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by_id")
    private Employee issuedBy;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
