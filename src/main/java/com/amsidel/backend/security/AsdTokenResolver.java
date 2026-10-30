/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/**
 * Reads the login token from "Authorization: asd &lt;token&gt;" (Amsidel's scheme name, in place of the
 * standard "Bearer"). Any other scheme, "Bearer" included, counts as no token.
 */
public class AsdTokenResolver implements BearerTokenResolver {

    /** Scheme name the web app and every API client must send. */
    public static final String SCHEME = "asd";

    private static final String PREFIX = SCHEME + " ";

    @Override
    public String resolve(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new InvalidBearerTokenException("Login token is missing after '" + SCHEME + "'");
        }
        return token;
    }
}
