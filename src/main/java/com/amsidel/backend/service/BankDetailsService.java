package com.amsidel.backend.service;

import com.amsidel.backend.dto.BankDetails;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * An employee's PAN and salary account, from the employee screen. Who sees them: the person, owners and admins who
 * manage them, and payroll (the Salaries menu, own brand). Who changes them here: owners and admins who manage the
 * person; payroll changes them on the salary page.
 */
@Service
@RequiredArgsConstructor
public class BankDetailsService {

    private final EmployeeService employeeService;
    private final MenuAccessService menuAccess;
    private final PayrollAccess payrollAccess;

    @Transactional(readOnly = true)
    public BankDetails.View get(AuthUser me, Long employeeId) {
        Employee e = employeeService.findVisible(me, employeeId);
        boolean manages = manages(me, employeeId);
        boolean payroll = menuAccess.can(me, Menus.PAYROLL_SALARIES) && payrollAccess.visible(me, employeeId) != null;
        if (!me.id().equals(employeeId) && !manages && !payroll) {
            throw ApiException.forbidden("Only the person, their admins and payroll see bank details");
        }
        return BankDetails.View.of(e, manages);
    }

    @Transactional
    public BankDetails.View save(AuthUser me, Long employeeId, BankDetails.Request body) {
        Employee e = employeeService.findManageable(me, employeeId);
        e.setPanNumber(clean(body.getPan(), true));
        e.setBankAccountNumber(clean(body.getBankAccount(), false));
        e.setIfscCode(clean(body.getIfsc(), true));
        String upi = clean(body.getUpi(), false);
        e.setUpiId(upi == null ? null : upi.toLowerCase(Locale.ROOT));
        return BankDetails.View.of(e, true);
    }

    private boolean manages(AuthUser me, Long employeeId) {
        if (!me.isOwner() && me.role() != com.amsidel.backend.entity.Role.ADMIN) {
            return false;
        }
        try {
            employeeService.findManageable(me, employeeId);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    private static String clean(String s, boolean upper) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return upper ? s.strip().toUpperCase(Locale.ROOT) : s.strip();
    }
}
