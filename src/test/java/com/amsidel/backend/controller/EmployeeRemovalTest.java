/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.repository.EmployeeRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Deleting employees: only with the tick, and only those with nothing to their name. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=employeeremoval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmployeeRemovalTest {

    private static final String API = "/api/employee-removal";
    private static final String TICK = "4008";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EmployeeRepository employees;

    private String owner;
    private String ownerId;
    private int dw;
    private int other;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        String res = body(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        owner = JsonPath.read(res, "$.token");
        ownerId = String.valueOf((Object) JsonPath.read(res, "$.user.id"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.getFirst();
        other = JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"RM\",\"name\":\"Removal Other\"}")), "$.id");
    }

    /** {id, userId}. */
    private String[] person(int brand, String extra, String... checkpoints) throws Exception {
        String userId = "rm." + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(json(post("/api/employees"), owner, """
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"%s}
                """.formatted(seq, userId, brand, extra)).andExpect(status().isCreated())), "$.id"));
        if (checkpoints.length > 0) {
            json(put("/api/menu-access/EMPLOYEE/" + id), owner, "{\"checkpoints\":[\"" + String.join("\",\"", checkpoints) + "\"]}").andExpect(status().isOk());
        }
        return new String[]{id, userId};
    }

    private String signIn(String[] p) throws Exception {
        return token(changePin(token(login(p[1], "ab@123")), "ab@123", "cd@456"));
    }

    private ResultActions remove(String token, String... ids) throws Exception {
        return json(post(API), token, "{\"ids\":[" + String.join(",", ids) + "]}");
    }

    private boolean exists(String[] p) {
        return employees.findById(Long.valueOf(p[0])).isPresent();
    }

    @Test
    void someoneUnusedGoesWithWhatIsTheirsAlone() throws Exception {
        String[] p = person(dw, "", "400", "3001");
        // signed in, chose a PIN, set a photo and their own ticks: none of that is history
        String token = signIn(p);
        mockMvc.perform(multipart("/api/auth/me/photo").file(new MockMultipartFile("file", "me.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0})).header("Authorization", auth(token))).andExpect(status().isOk());

        remove(owner, p[0]).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(1)).andExpect(jsonPath("$.notDeleted", hasSize(0)));
        assertThat(exists(p)).isFalse();
        mockMvc.perform(get("/api/employees/" + p[0]).header("Authorization", auth(owner))).andExpect(status().isNotFound());
        // their token stops working at once
        mockMvc.perform(get("/api/auth/me").header("Authorization", auth(token))).andExpect(status().isUnauthorized());
        // their menu access row is gone with them
        mockMvc.perform(get("/api/menu-access").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.subjectType == 'EMPLOYEE' && @.subjectKey == '" + p[0] + "')]", hasSize(0)));
        // and their user ID is free again
        json(post("/api/employees"), owner, """
                {"name":"Again","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(p[1], dw)).andExpect(status().isCreated());
    }

    @Test
    void anyoneWithHistoryStaysJustAsTheyWere() throws Exception {
        String[] worker = person(dw, "", "3001");
        String token = signIn(worker);
        String task = JsonPath.read(body(json(post("/api/tasks"), owner, "{\"title\":\"Real work\",\"brandId\":" + dw + ",\"assigneeIds\":[" + worker[0] + "]}").andExpect(status().isCreated())), "$.code");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        json(post("/api/worklog/entries"), token, """
                {"workDate":"%s","taskId":"%s","hours":1,"description":"Did it"}
                """.formatted(today, task)).andExpect(status().isCreated());

        remove(owner, worker[0]).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(0))
                .andExpect(jsonPath("$.notDeleted[0].reason", containsString("records")));
        assertThat(exists(worker)).isTrue();
        // everything refused was put back: still signed in, the ticks still there, the log still there
        mockMvc.perform(get("/api/tasks/mine").header("Authorization", auth(token))).andExpect(status().isOk());
        mockMvc.perform(get("/api/worklog").param("from", today.toString()).param("to", today.toString()).header("Authorization", auth(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries", hasSize(1)));
    }

    @Test
    void someoneWithPeopleUnderThemStays() throws Exception {
        String[] boss = person(dw, "");
        String[] under = person(dw, ",\"reportsToId\":" + boss[0]);
        remove(owner, boss[0]).andExpect(jsonPath("$.deleted").value(0)).andExpect(jsonPath("$.notDeleted[0].reason", containsString("report to them")));
        assertThat(exists(boss)).isTrue();
        // the person under them can go, and then they can too
        remove(owner, under[0], boss[0]).andExpect(jsonPath("$.deleted").value(2));
    }

    @Test
    void oneRefusedDoesNotStopTheOthers() throws Exception {
        String[] a = person(dw, "");
        String[] b = person(dw, "");
        String[] worker = person(dw, "");
        json(post("/api/tasks"), owner, "{\"title\":\"Assigned\",\"brandId\":" + dw + ",\"assigneeIds\":[" + worker[0] + "]}").andExpect(status().isCreated());
        remove(owner, a[0], worker[0], "999999", b[0]).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(2)).andExpect(jsonPath("$.notDeleted", hasSize(2)));
        assertThat(exists(a)).isFalse();
        assertThat(exists(b)).isFalse();
        assertThat(exists(worker)).isTrue();
    }

    @Test
    void onlyWithTheTickNeverYourselfOrAnOwnerAndOnlyYourBrand() throws Exception {
        String[] target = person(dw, "");
        String[] elsewhere = person(other, "");
        String plain = signIn(person(dw, ""));
        remove(plain, target[0]).andExpect(status().isForbidden());

        String[] given = person(dw, "", TICK);
        String token = signIn(given);
        // not an owner, not themselves, not another brand's
        remove(token, ownerId).andExpect(jsonPath("$.deleted").value(0)).andExpect(jsonPath("$.notDeleted[0].reason", containsString("owner")));
        remove(token, given[0]).andExpect(jsonPath("$.deleted").value(0)).andExpect(jsonPath("$.notDeleted[0].reason", containsString("your own")));
        remove(token, elsewhere[0]).andExpect(jsonPath("$.deleted").value(0));
        assertThat(exists(elsewhere)).isTrue();
        remove(token, target[0]).andExpect(jsonPath("$.deleted").value(1));
        assertThat(exists(target)).isFalse();

        // even the owner cannot delete an owner, themselves included
        remove(owner, ownerId).andExpect(jsonPath("$.deleted").value(0));
        json(post(API), owner, "{\"ids\":[]}").andExpect(status().isBadRequest());
    }

    @Test
    void aGeneratedBatchCanBeCleanedUp() throws Exception {
        List<Integer> designations = JsonPath.read(body(mockMvc.perform(get("/api/designations").header("Authorization", auth(owner)))), "$[?(@.brandId == null && @.active == true)].id");
        int brand = JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"RB\",\"name\":\"Batch\"}")), "$.id");
        json(post("/api/employee-generator"), owner, """
                {"brandId":%d,"designationId":%d,"count":3,"password":"Abc#123","temporaryPassword":false}
                """.formatted(brand, designations.getFirst())).andExpect(status().isCreated());
        // one of them used it, and it does not count as history
        login("rb0002", "Abc#123").andExpect(status().isOk());
        List<Integer> batch = JsonPath.read(body(mockMvc.perform(get("/api/employees").param("brandId", String.valueOf(brand)).header("Authorization", auth(owner)))), "$[*].id");
        assertThat(batch).hasSize(3);
        remove(owner, batch.stream().map(String::valueOf).toArray(String[]::new)).andExpect(jsonPath("$.deleted").value(3));
        // and the numbers start again from the top of what is left
        mockMvc.perform(get("/api/employee-generator/next").param("brandId", String.valueOf(brand)).header("Authorization", auth(owner))).andExpect(jsonPath("$.next").value("RB0001"));
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
