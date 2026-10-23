package com.amsidel.backend.service;

import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Whose pay a payroll user (owner, admin, HR with the Payroll menus) works with: owners and people without a brand
 * see everyone; anyone else sees the people of their own brand. Nobody changes their own pay, except an owner.
 */
@Component
@RequiredArgsConstructor
public class PayrollAccess {

    private final EmployeeRepository employeeRepository;

    public List<Employee> people(AuthUser me) {
        Long brandId = me.isOwner() || me.brandId() == null ? null : me.brandId();
        return employeeRepository.search(brandId);
    }

    public Employee visible(AuthUser me, Long employeeId) {
        Employee e = employeeRepository.findWithBrandById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));
        boolean all = me.isOwner() || me.brandId() == null;
        if (!all && (e.getBrand() == null || !me.brandId().equals(e.getBrand().getId()))) {
            throw ApiException.forbidden("You can only see the payroll of your own brand");
        }
        return e;
    }

    public boolean editable(AuthUser me, Employee e) {
        return me.isOwner() || !me.id().equals(e.getId());
    }

    public void requireEditable(AuthUser me, Employee e) {
        if (!editable(me, e)) {
            throw ApiException.forbidden("Your own pay is set by someone else");
        }
    }
}
