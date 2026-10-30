/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import java.util.List;

/** A menu with its sub-menus, in display order. */
public record MenuNode(String code, String name, String description, int checkpoint, String path, String icon,
                       List<MenuNode> children) {
}
