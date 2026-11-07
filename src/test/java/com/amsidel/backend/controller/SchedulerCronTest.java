/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.AppConfiguration;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.repository.AppConfigurationRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.scheduler.JobScheduler;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Each job's cron from the configurations table, with its timer really running: the session check, whose work can be seen on
 * an employee. Faster than the page allows is set straight in the table, so a test need not wait minutes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=schedulercron", "app.schedulers.auto-start=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulerCronTest {

    private static final String API = "/api/configurations";
    private static final String EVERY_SECOND = "* * * * * *";
    private static final String ONCE_A_YEAR = "0 0 0 1 1 *";
    private static final String SESSION = Job.SESSION_CHECK.cronCode();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppConfigurationRepository configurations;
    @Autowired
    private ConfigValues config;
    @Autowired
    private JobScheduler jobs;
    @Autowired
    private SchedulerSwitch schedulers;
    @Autowired
    private EmployeeRepository employees;

    private String owner;
    private String ownerUser;
    private int seq;
    private int dw;

    @BeforeAll
    void setUp() throws Exception {
        String first = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"owner\",\"password\":\"Ams@2026\"}")).andReturn().getResponse().getContentAsString();
        owner = JsonPath.read(mockMvc.perform(post("/api/auth/change-password").header("Authorization", "asd " + JsonPath.read(first, "$.token"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"Ams@2026\",\"newPassword\":\"Own#2026\"}")).andReturn().getResponse().getContentAsString(), "$.token");
        ownerUser = "owner";
        List<Integer> ids = JsonPath.read(mockMvc.perform(get("/api/brands").header("Authorization", "asd " + owner)).andReturn().getResponse().getContentAsString(), "$[?(@.code == 'DW')].id");
        dw = ids.getFirst();
    }

    @AfterEach
    void backToNormal() {
        direct(SESSION, ONCE_A_YEAR, true);
        direct(Job.SESSION_CHECK.code(), "true", true);
        direct(SchedulerSwitch.ALL, "true", true);
    }

    /** As someone changing the table straight in the database, then the watch that notices. */
    private void direct(String code, String value, boolean active) {
        AppConfiguration c = configurations.findById(code).orElseThrow();
        c.setValue(value);
        c.setActive(active);
        configurations.save(c);
        config.reload();
        jobs.refresh();
    }

    private ResultActions change(String code, String value, boolean active) throws Exception {
        return mockMvc.perform(put(API + "/" + code).header("Authorization", "asd " + owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + value + "\",\"active\":" + active + "}"));
    }

    /** Someone whose sign-in ran out days ago, not marked yet. */
    private Long overdue() throws Exception {
        String userId = "cr." + (++seq);
        Number id = JsonPath.read(mockMvc.perform(post("/api/employees").header("Authorization", "asd " + owner).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, dw))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        Employee e = employees.findById(id.longValue()).orElseThrow();
        e.setLastLoginAt(Instant.now().minus(Duration.ofDays(2)));
        employees.save(e);
        return e.getId();
    }

    private boolean marked(Long id) {
        return Boolean.TRUE.equals(employees.findById(id).orElseThrow().getSessionExpired());
    }

    private boolean markedWithin(Long id, long millis) throws Exception {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            if (marked(id)) {
                return true;
            }
            Thread.sleep(100);
        }
        return marked(id);
    }

    @Test
    void everyJobHasACronRowWithTheDefaultsFromTheProperties() throws Exception {
        for (Job j : Job.values()) {
            assertThat(configurations.findById(j.cronCode())).as(j.name()).isPresent();
        }
        assertThat(configurations.findById(Job.PRODUCT_COUNT.cronCode()).orElseThrow().getValue()).isEqualTo("0 */5 * * * *");
        assertThat(configurations.findById(Job.CHAT_REPORT.cronCode()).orElseThrow().getValue()).isEqualTo("0 0 * * * *");
        assertThat(configurations.findById(Job.LEAVE_EXPIRY.cronCode()).orElseThrow().getValue()).isEqualTo("0 0 8 * * *");
        // the old interval row is not read any more, and is not made
        assertThat(configurations.findById("SESSION_CHECK_MINUTES")).isEmpty();

        String code = Job.PRODUCT_COUNT.cronCode();
        mockMvc.perform(get(API).header("Authorization", "asd " + owner))
                .andExpect(jsonPath("$[?(@.code == '" + code + "')].usedByPortal", contains(true)))
                .andExpect(jsonPath("$[?(@.code == '" + code + "')].inEffect", contains("0 */5 * * * *")))
                .andExpect(jsonPath("$[?(@.code == '" + code + "')].fallback", contains("0 */5 * * * *")))
                .andExpect(jsonPath("$[?(@.code == '" + code + "')].detail", contains(startsWith("Next run: "))))
                .andExpect(jsonPath("$[?(@.code == '" + code + "')].hint", contains(containsString("6 parts"))))
                .andExpect(jsonPath("$[?(@.code == '" + code + "')].choices[*]", hasSize(0)));
    }

    @Test
    void thePageRefusesACronThatCannotWork() throws Exception {
        String code = Job.PRODUCT_COUNT.cronCode();
        change(code, "every day", true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("not a valid cron")));
        // five parts, as in Linux: this one takes six
        change(code, "0 8 * * *", true).andExpect(status().isBadRequest());
        change(code, "* * * * * *", true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("once a minute")));
        change(code, "*/30 * * * * *", true).andExpect(status().isBadRequest());
        // 31 February
        change(code, "0 0 0 31 2 *", true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("never")));
        assertThat(schedulers.cron(Job.PRODUCT_COUNT)).isEqualTo("0 */5 * * * *");

        change(code, "0 * * * * *", true).andExpect(status().isOk()).andExpect(jsonPath("$.inEffect").value("0 * * * * *"));
        change(code, "0 30 9 * * MON", true).andExpect(status().isOk()).andExpect(jsonPath("$.detail", containsString("Mon")));
        // inactive, or a bad value typed straight in the table: the properties file's
        change(code, "0 30 9 * * MON", false).andExpect(jsonPath("$.inEffect").value("0 */5 * * * *")).andExpect(jsonPath("$.inEffectNote").value("from the properties file"));
        direct(code, "nonsense", true);
        assertThat(schedulers.cron(Job.PRODUCT_COUNT)).isEqualTo("0 */5 * * * *");
        direct(code, "0 */5 * * * *", true);
    }

    @Test
    void aNewCronReschedulesTheRunningTimerWithNoRestart() throws Exception {
        direct(SESSION, ONCE_A_YEAR, true);
        Long waiting = overdue();
        Thread.sleep(2500);
        assertThat(marked(waiting)).as("at once a year it has not run").isFalse();

        // the running job now picks it up within a second or two
        direct(SESSION, EVERY_SECOND, true);
        assertThat(markedWithin(waiting, 4000)).as("every second, it has run").isTrue();

        // and back: it stops coming round
        direct(SESSION, ONCE_A_YEAR, true);
        Thread.sleep(1200);
        Long later = overdue();
        Thread.sleep(2500);
        assertThat(marked(later)).as("once a year again, it has not run").isFalse();
    }

    @Test
    void offAndOnStillWorkAndKeepTheTimer() throws Exception {
        direct(SESSION, EVERY_SECOND, true);
        direct(Job.SESSION_CHECK.code(), "false", true);
        Thread.sleep(1200);
        Long off = overdue();
        Thread.sleep(2500);
        assertThat(marked(off)).as("its own row off").isFalse();
        mockMvc.perform(get(API).header("Authorization", "asd " + owner))
                .andExpect(jsonPath("$[?(@.code == '" + SESSION + "')].detail", contains("Stopped, so it does not run")))
                .andExpect(jsonPath("$[?(@.code == '" + SESSION + "')].inEffect", contains(EVERY_SECOND)));

        direct(Job.SESSION_CHECK.code(), "true", true);
        assertThat(markedWithin(off, 4000)).as("on again").isTrue();

        direct(SchedulerSwitch.ALL, "false", true);
        Thread.sleep(1200);
        Long allOff = overdue();
        Thread.sleep(2500);
        assertThat(marked(allOff)).as("all off").isFalse();
        direct(SchedulerSwitch.ALL, "true", true);
        assertThat(markedWithin(allOff, 4000)).as("all on again").isTrue();
    }

    @Test
    void aChangeOnThePageTakesEffectAtOnceWithoutWaitingForTheWatch() throws Exception {
        // once a year set directly; the page's own smallest is a minute, so the timer is seen through what it reports
        change(SESSION, "0 15 3 * * *", true).andExpect(status().isOk()).andExpect(jsonPath("$.inEffect").value("0 15 3 * * *"))
                .andExpect(jsonPath("$.detail", startsWith("Next run: ")));
        assertThat(schedulers.cron(Job.SESSION_CHECK)).isEqualTo("0 15 3 * * *");
        assertThat(ownerUser).isNotBlank();
        mockMvc.perform(get(API).header("Authorization", "asd " + owner)).andExpect(jsonPath("$[*].code", not(contains("SESSION_CHECK_MINUTES"))));
    }
}
