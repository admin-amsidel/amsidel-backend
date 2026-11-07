/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.repository.AppConfigurationRepository;
import com.amsidel.backend.service.SchedulerSwitch;
import com.amsidel.backend.service.SchedulerSwitch.Job;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** A machine with app.schedulers.enabled=false runs no jobs, whatever the shared table says, and leaves the table as it is for the others. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=schedulermachineoff", "app.schedulers.enabled=false"})
class SchedulerMachineOffTest {

    @Autowired
    private SchedulerSwitch schedulers;
    @Autowired
    private AppConfigurationRepository configurations;

    @Test
    void noJobRunsHereAndTheSharedRowsStayOn() {
        for (Job j : Job.values()) {
            assertThat(schedulers.allowed(j)).as(j.name()).isFalse();
            assertThat(configurations.findById(j.code())).as(j.name()).get().satisfies(c -> {
                assertThat(c.getValue()).isEqualTo("true");
                assertThat(c.isActive()).isTrue();
            });
        }
        assertThat(configurations.findById(SchedulerSwitch.ALL)).get().satisfies(c -> assertThat(c.getValue()).isEqualTo("true"));
        assertThat(schedulers.inEffect(SchedulerSwitch.ALL)).isEqualTo("false");
    }
}
