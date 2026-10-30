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
import com.amsidel.backend.security.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Service
public class AuthService {

    private static final String WRONG_LOGIN = "Wrong user ID or PIN";

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final int maxFailedAttempts;
    private final Duration lockDuration;
    /** Checked against when the user ID does not exist, so both cases take the same time. */
    private final String dummyHash;

    public AuthService(EmployeeRepository employeeRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       @Value("${app.login.max-failed-attempts}") int maxFailedAttempts,
                       @Value("${app.login.lock-duration}") Duration lockDuration) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = lockDuration;
        this.dummyHash = passwordEncoder.encode("no-such-user-1!");
    }

    public static String normalizeUserId(String userId) {
        return userId == null ? null : userId.trim().toLowerCase(Locale.ROOT);
    }

    /** Failed attempts must be saved even though the request fails, hence noRollbackFor. */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse login(LoginRequest request) {
        Employee employee = employeeRepository.findByUserId(normalizeUserId(request.getUserId())).orElse(null);
        if (employee == null) {
            passwordEncoder.matches(request.getPassword(), dummyHash);
            throw ApiException.unauthorized(WRONG_LOGIN);
        }

        Instant now = Instant.now();
        if (employee.getLockedUntil() != null && employee.getLockedUntil().isAfter(now)) {
            throw ApiException.unauthorized(lockedMessage());
        }

        if (!passwordEncoder.matches(request.getPassword(), employee.getPasswordHash())) {
            int attempts = employee.getFailedAttempts() + 1;
            if (attempts >= maxFailedAttempts) {
                employee.setFailedAttempts(0);
                employee.setLockedUntil(now.plus(lockDuration));
                throw ApiException.unauthorized(lockedMessage());
            }
            employee.setFailedAttempts(attempts);
            throw ApiException.unauthorized(WRONG_LOGIN);
        }

        // checked after the PIN, so a wrong guess never reveals that an account exists but is disabled
        if (!employee.isActive()) {
            throw ApiException.unauthorized("This account is disabled. Please contact your admin.");
        }

        employee.setFailedAttempts(0);
        employee.setLockedUntil(null);
        employee.setLastLoginAt(now);
        return issue(employee);
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

    private String lockedMessage() {
        return "Too many wrong attempts. Try again after " + lockDuration.toMinutes() + " minutes.";
    }
}
