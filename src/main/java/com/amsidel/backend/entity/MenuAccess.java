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
 * One checkpoint given to a person, a user type or a designation: a checkpoint number (that menu and its
 * sub-menus) or "*" (every menu). A person with rows of their own gets exactly those; anyone else gets everything
 * given to their user type plus everything given to their designation. Owners always have everything.
 */
@Entity
@Table(name = "menu_access",
        uniqueConstraints = @UniqueConstraint(name = "uk_menu_access", columnNames = {"subject_type", "subject_key", "checkpoint"}))
@Getter
@Setter
@NoArgsConstructor
public class MenuAccess {

    public static final String ALL = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private AccessSubject subjectType;

    /** The role name (ROLE), the designation id (DESIGNATION) or the employee id (EMPLOYEE). */
    @Column(name = "subject_key", nullable = false, length = 40)
    private String subjectKey;

    /** A checkpoint number, or "*" for every menu. */
    @Column(nullable = false, length = 10)
    private String checkpoint;

    public static MenuAccess of(AccessSubject type, String key, String checkpoint) {
        MenuAccess a = new MenuAccess();
        a.setSubjectType(type);
        a.setSubjectKey(key);
        a.setCheckpoint(checkpoint);
        return a;
    }
}
