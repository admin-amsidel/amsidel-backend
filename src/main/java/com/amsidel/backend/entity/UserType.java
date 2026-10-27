package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A user type (portal access) an employee is given: the four built in (Owner, Admin, Manager, Employee, code = the
 * role name) and any added by owners and admins, e.g. "Accountant". Each stands on an access level (its base role:
 * admin, manager or employee) that decides what the security, approvals and teams allow; its menus are set in Menu
 * access like any user type. Built-in ones cannot be deleted; others only while no employee has them.
 */
@Entity
@Table(name = "user_types")
@Getter
@Setter
@NoArgsConstructor
public class UserType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** OWNER, ADMIN, MANAGER, EMPLOYEE, or made from the name (ACCOUNTANT); the key of its menu access. */
    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    /** The access level it stands on. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role baseRole;

    @Column(nullable = false)
    private boolean builtIn;

    /** Switched off: not given to new employees; those who have it keep it. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 200)
    private String description;

    private Instant createdAt;

    private Instant updatedAt;
}
