/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.service.SessionSettings;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Administration > Configuration: adding, changing and deleting, behind its own tick. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=configuration", "app.session.check-initial-delay=PT24H"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigurationTest {

    private static final String API = "/api/configurations";
    private static final String TICK = "9020";
    private static final String CRON = com.amsidel.backend.service.SchedulerSwitch.Job.SESSION_CHECK.cronCode();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SessionSettings sessions;

    private String owner;
    private int dw;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.getFirst();
    }

    private String person(String... checkpoints) throws Exception {
        String userId = "cf." + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(json(post("/api/employees"), owner, """
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, dw)).andExpect(status().isCreated())), "$.id"));
        if (checkpoints.length > 0) {
            json(put("/api/menu-access/EMPLOYEE/" + id), owner, "{\"checkpoints\":[\"" + String.join("\",\"", checkpoints) + "\"]}").andExpect(status().isOk());
        }
        return token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
    }

    @Test
    void addChangeAndDelete() throws Exception {
        json(post(API), owner, "{\"code\":\"my_setting\",\"description\":\" A setting \",\"value\":\" 12 \",\"active\":true}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("MY_SETTING")).andExpect(jsonPath("$.description").value("A setting")).andExpect(jsonPath("$.value").value("12"))
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.usedByPortal").value(false)).andExpect(jsonPath("$.inEffect").doesNotExist())
                .andExpect(jsonPath("$.updatedByName").isNotEmpty()).andExpect(jsonPath("$.updatedAt").exists());
        // the same code, in any case, cannot be added twice
        json(post(API), owner, "{\"code\":\"MY_SETTING\",\"value\":\"13\"}").andExpect(status().isConflict());
        json(post(API), owner, "{\"code\":\"has space\",\"value\":\"1\"}").andExpect(status().isBadRequest());
        json(post(API), owner, "{\"code\":\"NO_VALUE\",\"value\":\" \"}").andExpect(status().isBadRequest());

        json(put(API + "/MY_SETTING"), owner, "{\"description\":null,\"value\":\"99\",\"active\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("99")).andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.description").doesNotExist());
        mockMvc.perform(get(API).header("Authorization", auth(owner))).andExpect(jsonPath("$[?(@.code == 'MY_SETTING')].value", contains("99")));

        mockMvc.perform(delete(API + "/MY_SETTING").header("Authorization", auth(owner))).andExpect(status().isNoContent());
        mockMvc.perform(get(API).header("Authorization", auth(owner))).andExpect(jsonPath("$[*].code", not(hasItem("MY_SETTING"))));
        mockMvc.perform(delete(API + "/MY_SETTING").header("Authorization", auth(owner))).andExpect(status().isNotFound());
        json(put(API + "/NOT_THERE"), owner, "{\"value\":\"1\"}").andExpect(status().isNotFound());
    }

    @Test
    void aValueThePortalReadsIsCheckedAndAppliesAtOnce() throws Exception {
        String max = API + "/" + SessionSettings.MAX_MINUTES;
        for (String bad : List.of("soon", "0", "-5", "4", "43201", "1.5")) {
            json(put(max), owner, "{\"value\":\"" + bad + "\",\"active\":true}").andExpect(status().isBadRequest());
        }
        json(put(API + "/" + CRON), owner, "{\"value\":\"0\",\"active\":true}").andExpect(status().isBadRequest());
        // nothing refused was saved
        assertThat(sessions.maxValidity()).isEqualTo(Duration.ofMinutes(360));

        json(put(max), owner, "{\"description\":\"Sign-in time\",\"value\":\"120\",\"active\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.usedByPortal").value(true)).andExpect(jsonPath("$.inEffect").value("120")).andExpect(jsonPath("$.fallback").value("360"));
        assertThat(sessions.maxValidity()).isEqualTo(Duration.ofMinutes(120));
        // inactive: the properties file's again
        json(put(max), owner, "{\"value\":\"120\",\"active\":false}").andExpect(jsonPath("$.inEffect").value("360"));
        assertThat(sessions.maxValidity()).isEqualTo(Duration.ofMinutes(360));
        json(put(max), owner, "{\"value\":\"90\",\"active\":true}").andExpect(status().isOk());
        assertThat(sessions.maxValidity()).isEqualTo(Duration.ofMinutes(90));
        // deleted: the same; the row comes back at the next start
        mockMvc.perform(delete(max).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        assertThat(sessions.maxValidity()).isEqualTo(Duration.ofMinutes(360));
        sessions.seed();
        mockMvc.perform(get(API).header("Authorization", auth(owner))).andExpect(jsonPath("$[?(@.code == '" + SessionSettings.MAX_MINUTES + "')].value", contains("360")));
    }

    @Test
    void onlyOwnersAndThoseGivenTheTick() throws Exception {
        // the default employee, and someone with the rest of Administration's menus, have no way in
        String plain = person();
        String otherAdminPages = person("9010", "9002");
        for (String token : List.of(plain, otherAdminPages)) {
            mockMvc.perform(get(API).header("Authorization", auth(token))).andExpect(status().isForbidden());
            json(post(API), token, "{\"code\":\"NOPE\",\"value\":\"1\"}").andExpect(status().isForbidden());
            json(put(API + "/" + CRON), token, "{\"value\":\"7\"}").andExpect(status().isForbidden());
            mockMvc.perform(delete(API + "/" + CRON).header("Authorization", auth(token))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/auth/access").header("Authorization", auth(plain))).andExpect(jsonPath("$.codes", not(hasItem("CONFIGURATIONS"))));

        String given = person(TICK);
        mockMvc.perform(get(API).header("Authorization", auth(given))).andExpect(status().isOk());
        json(post(API), given, "{\"code\":\"GIVEN\",\"value\":\"1\"}").andExpect(status().isCreated());
        mockMvc.perform(delete(API + "/GIVEN").header("Authorization", auth(given))).andExpect(status().isNoContent());
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, String content) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private ResultActions changePin(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password").header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String token(ResultActions result) throws Exception {
        return JsonPath.read(body(result), "$.token");
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
