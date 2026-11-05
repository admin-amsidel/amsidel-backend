/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.ChangePasswordRequest;
import com.amsidel.backend.dto.EmployeeResponse;
import com.amsidel.backend.dto.LoginRequest;
import com.amsidel.backend.dto.LoginResponse;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.PinChecks;
import com.amsidel.backend.security.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;

@lombok.extern.slf4j.Slf4j
@Service
public class AuthService {

    private static final String WRONG_LOGIN = "Wrong user ID or PIN";
    private static final String LOCKED = "Too many wrong attempts: your account is locked. Ask your admin to set a temporary PIN to release it.";
    private static final String DISABLED = "This account is disabled. Please contact your admin.";

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final PinChecks pinChecks;
    private final TransactionTemplate transaction;
    private final TokenService tokenService;
    private final int maxFailedAttempts;
    /** Checked against when the user ID does not exist, so both cases take the same time. */
    private final String dummyHash;

    public AuthService(EmployeeRepository employeeRepository,
                       PasswordEncoder passwordEncoder,
                       PinChecks pinChecks,
                       TransactionTemplate transaction,
                       TokenService tokenService,
                       @Value("${app.login.max-failed-attempts}") int maxFailedAttempts) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.pinChecks = pinChecks;
        this.transaction = transaction;
        this.tokenService = tokenService;
        this.maxFailedAttempts = maxFailedAttempts;
        this.dummyHash = passwordEncoder.encode("no-such-user-1!");
    }

    public static String normalizeUserId(String userId) {
        return userId == null ? null : userId.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Deliberately not one transaction: checking a PIN is by far the slowest step, and a connection held through it is
     * one every other page in the portal has to wait for. So: read, give the connection back, check, then record the
     * result in a short write that looks again at the account, as it may have changed during the check.
     */
    public LoginResponse login(LoginRequest request) {
        Employee employee = employeeRepository.findByUserId(normalizeUserId(request.getUserId())).orElse(null);
        if (employee == null) {
            pinChecks.matches(request.getPassword(), dummyHash);
            throw ApiException.unauthorized(WRONG_LOGIN);
        }

        // even the right PIN is refused while locked (checked before it, so a locked account cannot be guessed at)
        if (isLocked(employee)) {
            throw ApiException.unauthorized(LOCKED);
        }

        String checkedHash = employee.getPasswordHash();
        if (!pinChecks.matches(request.getPassword(), checkedHash)) {
            // saved first and thrown after, so the error does not undo the count
            boolean locked = Boolean.TRUE.equals(transaction.execute(s -> recordWrongPin(employee.getId(), checkedHash)));
            throw ApiException.unauthorized(locked ? LOCKED : WRONG_LOGIN);
        }

        // checked after the PIN, so a wrong guess never reveals that an account exists but is disabled
        if (!employee.isActive()) {
            throw ApiException.unauthorized(DISABLED);
        }

        Instant now = Instant.now();
        if (employeeRepository.recordSignIn(employee.getId(), checkedHash, now) == 0) {
            throw changedDuringCheck(employee.getId());
        }
        employee.setFailedAttempts(0);
        employee.setLockedUntil(null);
        employee.setLastLoginAt(now);
        return issue(employee);
    }

    /**
     * After max-failed-attempts wrong PINs in a row the account locks until an admin sets a temporary PIN; a right PIN
     * in between starts the count again. Says whether the account is locked now. A guess at a PIN that has since been
     * replaced does not count against the new one.
     */
    private boolean recordWrongPin(Long id, String checkedHash) {
        Employee employee = employeeRepository.findForUpdate(id).orElse(null);
        if (employee == null || !checkedHash.equals(employee.getPasswordHash())) {
            return false;
        }
        if (isLocked(employee)) {
            return true;
        }
        int attempts = employee.getFailedAttempts() + 1;
        if (attempts >= maxFailedAttempts) {
            employee.setFailedAttempts(0);
            employee.setLockedAt(Instant.now());
            log.warn("Sign-in locked for {} after {} wrong PINs in a row; an admin must reset the PIN", employee.getUserId(), attempts);
            return true;
        }
        employee.setFailedAttempts(attempts);
        return false;
    }

    /** Why a right PIN no longer signs in: what happened to the account while it was being checked. */
    private ApiException changedDuringCheck(Long id) {
        Employee now = employeeRepository.findById(id).orElse(null);
        if (now == null) {
            return ApiException.unauthorized(WRONG_LOGIN);
        }
        if (isLocked(now)) {
            return ApiException.unauthorized(LOCKED);
        }
        return ApiException.unauthorized(now.isActive() ? WRONG_LOGIN : DISABLED);
    }

    private static boolean isLocked(Employee employee) {
        return employee.getLockedAt() != null || (employee.getLockedUntil() != null && employee.getLockedUntil().isAfter(Instant.now()));
    }

    @Transactional(readOnly = true)
    public EmployeeResponse me(AuthUser me) {
        return EmployeeResponse.from(load(me));
    }

    /** Replaces the PIN and returns a fresh token; tokens on other devices stop working. */
    @Transactional
    public LoginResponse changePassword(AuthUser me, ChangePasswordRequest request) {
        Employee employee = load(me);
        // 400 rather than 401: the session is fine, only the typed PIN is wrong
        if (!passwordEncoder.matches(request.getCurrentPassword(), employee.getPasswordHash())) {
            throw ApiException.badRequest("Current PIN is wrong");
        }
        if (request.getNewPassword().equals(request.getCurrentPassword())) {
            throw ApiException.badRequest("New PIN must be different from the current one");
        }
        employee.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        employee.setMustChangePassword(false);
        employee.setPasswordChangedAt(Instant.now());
        return issue(employee);
    }

    private LoginResponse issue(Employee employee) {
        TokenService.IssuedToken token = tokenService.issue(employee);
        return LoginResponse.of(token.token(), token.expiresAt(), EmployeeResponse.from(employee));
    }

    private Employee load(AuthUser me) {
        return employeeRepository.findWithBrandById(me.id())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + me.id()));
    }


}
