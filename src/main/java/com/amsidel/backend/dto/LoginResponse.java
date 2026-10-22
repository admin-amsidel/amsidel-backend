package com.amsidel.backend.dto;

import com.amsidel.backend.security.AsdTokenResolver;

import java.time.Instant;

/** Answer to a successful login or PIN change: send the token as "Authorization: asd &lt;token&gt;". */
public record LoginResponse(String token, String tokenType, Instant expiresAt, EmployeeResponse user) {

    public static LoginResponse of(String token, Instant expiresAt, EmployeeResponse user) {
        return new LoginResponse(token, AsdTokenResolver.SCHEME, expiresAt, user);
    }
}
