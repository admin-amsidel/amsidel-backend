/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.AppConfiguration;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.repository.AppConfigurationRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.scheduler.SessionExpiryScheduler;
import com.amsidel.backend.service.ConfigValues;
import com.amsidel.backend.service.SchedulerSwitch;
import com.amsidel.backend.service.SchedulerSwitch.Job;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stopping and starting the scheduled jobs from the configurations table. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=schedulerswitch", "app.session.check-initial-delay=PT24H"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulerSwitchTest {

    private static final String API = "/api/configurations";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SchedulerSwitch schedulers;
    @Autowired
    private AppConfigurationRepository configurations;
    @Autowired
    private ConfigValues config;
    @Autowired
    private SessionExpiryScheduler sessionCheck;
    @Autowired
    private EmployeeRepository employees;

    private String owner;

    @BeforeAll
    void setUp() throws Exception {
        owner = JsonPath.read(mockMvc.perform(post("/api/auth/change-password").header("Authorization", "asd " + JsonPath.read(
                mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"owner\",\"password\":\"Ams@2026\"}")).andReturn().getResponse().getContentAsString(), "$.token"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"Ams@2026\",\"newPassword\":\"Own#2026\"}")).andReturn().getResponse().getContentAsString(), "$.token");
    }

    @AfterEach
    void allOnAgain() {
        set(SchedulerSwitch.ALL, "true", true);
        for (Job j : Job.values()) {
            set(j.code(), "true", true);
        }
    }

    private void set(String code, String value, boolean active) {
        AppConfiguration c = configurations.findById(code).orElseThrow();
        c.setValue(value);
        c.setActive(active);
        configurations.save(c);
        config.reload();
    }

    private ResultActions change(String code, String value, boolean active) throws Exception {
        return mockMvc.perform(put(API + "/" + code).header("Authorization", "asd " + owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + value + "\",\"active\":" + active + "}"));
    }

    @Test
    void everyJobHasARowAndRunsByDefault() throws Exception {
        for (Job j : Job.values()) {
            assertThat(schedulers.allowed(j)).as(j.name()).isTrue();
            assertThat(configurations.findById(j.code())).as(j.name()).get().satisfies(c -> assertThat(c.getValue()).isEqualTo("true"));
        }
        mockMvc.perform(get(API).header("Authorization", "asd " + owner))
                .andExpect(jsonPath("$[?(@.code == '" + SchedulerSwitch.ALL + "')].usedByPortal", contains(true)))
                .andExpect(jsonPath("$[?(@.code == '" + SchedulerSwitch.ALL + "')].choices[*]", contains("true", "false")))
                .andExpect(jsonPath("$[?(@.code == '" + SchedulerSwitch.ALL + "')].inEffect", contains("true")));
    }

    @Test
    void oneJobStopsAloneAndStartsAgainWithNoRestart() throws Exception {
        change(Job.PRODUCT_COUNT.code(), "false", true).andExpect(status().isOk()).andExpect(jsonPath("$.inEffect").value("false"));
        assertThat(schedulers.allowed(Job.PRODUCT_COUNT)).isFalse();
        assertThat(schedulers.allowed(Job.CHAT_CLEANUP)).isTrue();
        change(Job.PRODUCT_COUNT.code(), "TRUE", true).andExpect(status().isOk()).andExpect(jsonPath("$.inEffect").value("true"));
        assertThat(schedulers.allowed(Job.PRODUCT_COUNT)).isTrue();
    }

    @Test
    void theAllRowStopsEveryJobAndSaysSo() throws Exception {
        change(SchedulerSwitch.ALL, "false", true).andExpect(status().isOk());
        for (Job j : Job.values()) {
            assertThat(schedulers.allowed(j)).as(j.name()).isFalse();
        }
        mockMvc.perform(get(API).header("Authorization", "asd " + owner))
                .andExpect(jsonPath("$[?(@.code == '" + Job.CHAT_REPORT.code() + "')].inEffect", contains("false")))
                .andExpect(jsonPath("$[?(@.code == '" + Job.CHAT_REPORT.code() + "')].inEffectNote", contains("stopped by " + SchedulerSwitch.ALL)));
    }

    @Test
    void anInactiveOrMissingRowMeansItRuns() throws Exception {
        change(Job.LEAVE_EXPIRY.code(), "false", false).andExpect(status().isOk()).andExpect(jsonPath("$.inEffect").value("true"))
                .andExpect(jsonPath("$.inEffectNote").value("no active row, so it runs"));
        assertThat(schedulers.allowed(Job.LEAVE_EXPIRY)).isTrue();
        // a mistake typed straight in the database: runs, rather than stopping without a word
        set(Job.CHAT_CLEANUP.code(), "maybe", true);
        assertThat(schedulers.allowed(Job.CHAT_CLEANUP)).isTrue();
    }

    @Test
    void onlyTrueOrFalseIsTaken() throws Exception {
        change(SchedulerSwitch.ALL, "off", true).andExpect(status().isBadRequest());
        change(Job.PRODUCT_COUNT.code(), "0", true).andExpect(status().isBadRequest());
        assertThat(schedulers.allowed(Job.PRODUCT_COUNT)).isTrue();
    }

    @Test
    void aStoppedJobDoesItsWorkNoMore() {
        Employee someone = employees.findByUserId("owner").orElseThrow();
        Instant real = someone.getLastLoginAt();
        someone.setLastLoginAt(Instant.now().minus(Duration.ofDays(2)));
        someone.setSessionExpired(false);
        employees.save(someone);

        set(Job.SESSION_CHECK.code(), "false", true);
        sessionCheck.tick();
        assertThat(employees.findByUserId("owner").orElseThrow().getSessionExpired()).isFalse();

        set(Job.SESSION_CHECK.code(), "true", true);
        sessionCheck.tick();
        assertThat(employees.findByUserId("owner").orElseThrow().getSessionExpired()).isTrue();

        // leave the others of this class signed in
        someone = employees.findByUserId("owner").orElseThrow();
        someone.setLastLoginAt(real);
        someone.setSessionExpired(false);
        someone.setSessionExpiredAt(null);
        employees.save(someone);
    }
}
