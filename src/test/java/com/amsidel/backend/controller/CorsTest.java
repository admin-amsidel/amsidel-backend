/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The web addresses the browser may call the API from: amsidel.com, any of its sub-domains, and the listed others. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=cors",
        "app.cors.allowed-origins=https://amsidel.com, https://*.amsidel.com,https://amsidel-web.info-amsidel.workers.dev,http://localhost:4200"})
class CorsTest {

    @Autowired
    private MockMvc mockMvc;

    private ResultActions preflight(String origin) throws Exception {
        return mockMvc.perform(options("/api/auth/login").header("Origin", origin).header("Access-Control-Request-Method", "POST"));
    }

    @Test
    void amsidelAndEverySubDomainAreAllowed() throws Exception {
        for (String origin : new String[]{"https://amsidel.com", "https://www.amsidel.com", "https://portal.amsidel.com", "https://staff.amsidel.com",
                "https://amsidel-web.info-amsidel.workers.dev", "http://localhost:4200"}) {
            preflight(origin).andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }

    @Test
    void othersAreRefused() throws Exception {
        for (String origin : new String[]{"https://evil.com", "https://amsidel.com.evil.com", "https://notamsidel.com", "http://www.amsidel.com", "http://localhost:4300"}) {
            preflight(origin).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }
}
