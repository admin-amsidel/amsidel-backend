package com.amsidel.backend.security;

import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.Role;

/**
 * The signed-in person, available in controllers as {@code @AuthenticationPrincipal AuthUser me}.
 * Built from the database on every request, so a role or brand change applies immediately.
 */
public record AuthUser(Long id, String userId, Role role, Long brandId, Long designationId, boolean leadsTeam) {

    public static AuthUser of(Employee e) {
        return of(e, false);
    }

    /** leadsTeam: someone active reports to this person (opens the team menus that follow the reporting line). */
    public static AuthUser of(Employee e, boolean leadsTeam) {
        return new AuthUser(e.getId(), e.getUserId(), e.getRole(), e.getBrand() == null ? null : e.getBrand().getId(),
                e.getDesignation() == null ? null : e.getDesignation().getId(), leadsTeam);
    }

    public boolean isOwner() {
        return role == Role.OWNER;
    }

    /** Admin without a brand: manages people in every brand. */
    public boolean isGroupAdmin() {
        return role == Role.ADMIN && brandId == null;
    }
}
