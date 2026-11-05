/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Answers 503 when checking a token could not reach the database. That check runs before the controllers, so
 * GlobalExceptionHandler never sees it; the person's token is fine, and the portal signs people out on a 401, so a
 * busy moment must not look like one.
 */
@Slf4j
class DatabaseBusyFilter extends OncePerRequestFilter {

    private static final String BODY = "{\"status\":503,\"error\":\"The server is busy right now. Please try again in a moment.\"}";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (DataAccessException | TransactionException e) {
            if (response.isCommitted()) {
                throw e;
            }
            log.warn("Database busy during {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMostSpecificCause().getMessage());
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(BODY);
        }
    }
}
