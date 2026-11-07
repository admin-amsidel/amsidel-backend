/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * A rejected token gets a 401 that says why, in the same {"error": ...} form as every other API error. An expired or
 * unreadable token says the session expired; the checks in EmployeeTokenConverter give their own reason.
 */
public class SessionEntryPoint implements AuthenticationEntryPoint {

    public static final String SESSION_EXPIRED = "Session expired. Please log in again.";

    private final BearerTokenAuthenticationEntryPoint standard = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException reason) throws IOException {
        standard.commence(request, response, reason);
        if (reason instanceof InvalidBearerTokenException) {
            String message = reason.getMessage() != null && reason.getMessage().startsWith(EmployeeTokenConverter.OWN_REASON) ? reason.getMessage().substring(EmployeeTokenConverter.OWN_REASON.length()) : SESSION_EXPIRED;
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\",\"code\":\"SESSION_EXPIRED\"}");
        }
    }
}
