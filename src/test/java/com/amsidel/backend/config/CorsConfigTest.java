/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs with Spring Security on, since it applies CORS before checking tokens. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.amsidel.com",
            "https://amsidel.com",
            "https://amsidel-web.info-amsidel.workers.dev",
            "http://localhost:4200"
    })
    void allowsKnownOrigins(String origin) throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example.com", "https://amsidel.com.evil.net"})
    void rejectsUnknownOrigins(String origin) throws Exception {
        mockMvc.perform(options("/api/products")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
