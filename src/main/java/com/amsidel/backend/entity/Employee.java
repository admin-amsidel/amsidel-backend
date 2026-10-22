package com.amsidel.backend.entity;

import com.amsidel.backend.security.EmployeeCacheListener;
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
import java.time.LocalDateTime;

/** A person who can sign in to the portal: employees and owners of every brand in the group. */
@Entity
@Table(name = "employees")
@EntityListeners({AuditingEntityListener.class, EmployeeCacheListener.class})
@Getter
@Setter
@NoArgsConstructor
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String empNumber;

    /** Login id, stored in lower case. */
    @Column(nullable = false, unique = true, length = 50)
    private String userId;

    /** BCrypt hash of the PIN; the PIN itself is never stored. */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** Null for group-level people (owners, group admins). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    /** Job title and level (Trainee ... CEO); null until assigned. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "designation_id")
    private Designation designation;

    /** Direct superior; together with designations this is the group's reporting hierarchy. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reports_to_id")
    private Employee reportsTo;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(length = 20)
    private String phone;

    /** Path of the photo inside the web app's public folder, e.g. "users/DW0001.jpg". */
    @Column(length = 200)
    private String photoPath;

    /** When a photo was last uploaded (see EmployeePhoto); null = none. Shown in place of photoPath. */
    private Instant photoUpdatedAt;

    private LocalDate joinedDate;

    private LocalDate dob;

    @Column(length = 3)
    private String bloodGroup;

    @Column(length = 150)
    private String personalEmail;

    @Column(length = 150)
    private String companyEmail;

    @Column(length = 20)
    private String emergencyContact;

    /** This person's own minimum working hours, set by an owner, admin or their manager; null = their level's rule. */
    @Column(precision = 4, scale = 2)
    private BigDecimal minDailyHours;

    @Column(precision = 5, scale = 2)
    private BigDecimal minWeeklyHours;

    @Column(nullable = false)
    private boolean active = true;

    /** Set for new accounts and after an admin reset, so the person picks their own PIN. */
    @Column(nullable = false)
    private boolean mustChangePassword = true;

    @Column(nullable = false)
    private int failedAttempts;

    private Instant lockedUntil;

    /** Tokens issued before this moment are rejected (a PIN change or reset logs out other devices). */
    private Instant passwordChangedAt;

    private Instant lastLoginAt;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
