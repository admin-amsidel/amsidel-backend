package com.amsidel.backend.dto;

import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.Role;

/** Someone whose leave the signed-in approver decides; for the grant form's person picker. */
public record TeamMember(Long id, String name, String empNumber, Role role, String brandCode) {

    /** Call inside a transaction: reads the brand. */
    public static TeamMember from(Employee e) {
        return new TeamMember(e.getId(), e.getName(), e.getEmpNumber(), e.getRole(),
                e.getBrand() == null ? null : e.getBrand().getCode());
    }
}
