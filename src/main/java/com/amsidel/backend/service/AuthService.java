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

import java.time.Instant;
import java.util.Locale;

@lombok.extern.slf4j.Slf4j
@Service
public class AuthService {

    private static final String WRONG_LOGIN = "Wrong user ID or PIN";
    private static final String LOCKED = "Too many wrong attempts: your account is locked. Ask your admin to set a temporary PIN to release it.";

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final int maxFailedAttempts;
    /** Checked against when the user ID does not exist, so both cases take the same time. */
    private final String dummyHash;

    public AuthService(EmployeeRepository employeeRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       @Value("${app.login.max-failed-attempts}") int maxFailedAttempts) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.maxFailedAttempts = maxFailedAttempts;
        this.dummyHash = passwordEncoder.encode("no-such-user-1!");
    }

    public static String normalizeUserId(String userId) {
        return userId == null ? null : userId.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Failed attempts must be saved even though the request fails, hence noRollbackFor. After max-failed-attempts
     * wrong PINs in a row the account locks until an admin sets a temporary PIN; a right PIN in between starts the
     * count again.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse login(LoginRequest request) {
        Employee employee = employeeRepository.findByUserId(normalizeUserId(request.getUserId())).orElse(null);
        if (employee == null) {
            passwordEncoder.matches(request.getPassword(), dummyHash);
            throw ApiException.unauthorized(WRONG_LOGIN);
        }

        Instant now = Instant.now();
        // even the right PIN is refused while locked (checked before it, so a locked account cannot be guessed at)
        if (employee.getLockedAt() != null || (employee.getLockedUntil() != null && employee.getLockedUntil().isAfter(now))) {
            throw ApiException.unauthorized(LOCKED);
        }

        if (!passwordEncoder.matches(request.getPassword(), employee.getPasswordHash())) {
            int attempts = employee.getFailedAttempts() + 1;
            if (attempts >= maxFailedAttempts) {
                employee.setFailedAttempts(0);
                employee.setLockedAt(now);
                log.warn("Sign-in locked for {} after {} wrong PINs in a row; an admin must reset the PIN", employee.getUserId(), attempts);
                throw ApiException.unauthorized(LOCKED);
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


}
