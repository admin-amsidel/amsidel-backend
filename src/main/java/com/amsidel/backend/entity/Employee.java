/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
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

    /** The access level (from the user type: OWNER, ADMIN, MANAGER, EMPLOYEE): what security, approvals and teams follow. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** The user type (portal access) given; null = the built-in one of the role. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_type_id")
    private UserType userType;

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

    /** Approves this person's attendance and work log; null = the person they report to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_id")
    private Employee approver;

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

    /** PAN, shown on payslips; read and changed only through payroll (not in the general employee API). */
    @Column(length = 10)
    private String panNumber;

    /** Salary bank account number, shown on payslips; payroll only, like panNumber. */
    @Column(length = 30)
    private String bankAccountNumber;

    /** IFSC of the salary account's branch, e.g. HDFC0001234; payroll only, like panNumber. */
    @Column(length = 11)
    private String ifscCode;

    /** UPI ID for salary by UPI, e.g. anu@okhdfcbank; payroll only, like panNumber. */
    @Column(length = 80)
    private String upiId;

    /** The person's own sign-off for staff mail (**bold** allowed); null = Regards, name, designation, brand. */
    @Column(length = 1000)
    private String mailSignature;

    /** This person's own minimum working hours, set by an owner, admin or their manager; null = their level's rule. */
    @Column(precision = 4, scale = 2)
    private BigDecimal minDailyHours;

    @Column(precision = 5, scale = 2)
    private BigDecimal minWeeklyHours;

    @Column(nullable = false)
    private boolean active = true;

    /** Auto approve: leave, attendance and work log of the people this person approves for count at once (set by owners and admins); null = no. */
    @Column(name = "auto_approve_reports")
    private Boolean autoApproveReports;

    /** Documents that may be uploaded for this person in a month; null = the company default (app.documents.monthly-upload-limit). */
    private Integer documentUploadLimit;

    /** How many have been uploaded in {@link #documentUploadsMonth}. */
    private Integer documentUploadsUsed;

    /** The month that count is for, e.g. 2026-11; an older one means nothing has been uploaded this month. */
    @Column(length = 7)
    private String documentUploadsMonth;

    /** Set for new accounts and after an admin reset, so the person picks their own PIN. */
    @Column(nullable = false)
    private boolean mustChangePassword = true;

    @Column(nullable = false)
    private int failedAttempts;

    /** Old timed lock (before locks waited for an admin); still honoured until it runs out. */
    private Instant lockedUntil;

    /**
     * Locked after too many wrong PINs in a row: no sign-in at all until an admin sets a temporary PIN (Reset PIN),
     * which releases the account. Null = not locked.
     */
    private Instant lockedAt;

    /** Tokens issued before this moment are rejected (a PIN change or reset logs out other devices). */
    private Instant passwordChangedAt;

    private Instant lastLoginAt;

    /** Set when the sign-in ran past its time (by the session check) or the person signed out; cleared by signing in. Null = no. */
    @Column(name = "is_session_expired")
    private Boolean sessionExpired;

    /** Tokens issued before this moment are refused, even after a new sign-in clears the flag. */
    private Instant sessionExpiredAt;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
