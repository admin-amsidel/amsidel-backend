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
 * A portal menu or sub-menu, with its checkpoint number. Access is given per checkpoint in {@link MenuAccess};
 * giving a menu's checkpoint also gives all its sub-menus. Rows are kept in step with MenuCatalog at start-up.
 */
@Entity
@Table(name = "menus")
@Getter
@Setter
@NoArgsConstructor
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable name used in code, e.g. ATT_TEAM. */
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 300)
    private String description;

    /** e.g. 1002; a menu's sub-menus share its thousand (1000 → 1001, 1002). */
    @Column(nullable = false, unique = true)
    private int checkpoint;

    /** The menu this sub-menu sits under; null for top-level menus. */
    @Column(name = "parent_code", length = 40)
    private String parentCode;

    /** Portal address; null for a menu that only holds sub-menus. */
    @Column(length = 120)
    private String path;

    /** PrimeIcons name, e.g. pi-users. */
    @Column(length = 40)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
