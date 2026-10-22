package com.amsidel.backend.dto;

import java.util.List;

/** What the signed-in person may open: every menu (all), the menu codes, and the menu tree to show. */
public record MyAccess(boolean all, List<String> codes, List<MenuNode> menus) {
}
