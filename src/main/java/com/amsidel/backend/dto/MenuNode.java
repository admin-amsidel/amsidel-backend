package com.amsidel.backend.dto;

import java.util.List;

/** A menu with its sub-menus, in display order. */
public record MenuNode(String code, String name, String description, int checkpoint, String path, String icon,
                       List<MenuNode> children) {
}
