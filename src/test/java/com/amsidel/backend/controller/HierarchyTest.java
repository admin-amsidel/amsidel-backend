/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

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

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reporting lines: CEO -> Branch Head -> Team Lead -> Trainee, plus a peer of the trainee. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=hierarchy")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HierarchyTest {

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private String ceo;
    private String lead;
    private String trainee;
    private String peer;
    private String traineeToken;
    private String leadToken;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        long dw = brandIds.get(0);
        String levels = body(mockMvc.perform(get("/api/designations").header("Authorization", auth(owner))));

        ceo = create("Chitra CEO", "chitra", "ADMIN", null, level(levels, "CEO"), null);
        String head = create("Hari Head", "hari", "ADMIN", dw, level(levels, "Branch Manager / Head"), ceo);
        lead = create("Lakshmi Lead", "lakshmi", "MANAGER", dw, level(levels, "Team Lead"), head);
        trainee = create("Tom Trainee", "tom", "EMPLOYEE", dw, level(levels, "Trainee"), lead);
        peer = create("Priya Peer", "priya", "EMPLOYEE", dw, level(levels, "Team Member"), lead);
        traineeToken = token(changePin(token(login("tom", "ab@123")), "ab@123", "cd@456"));
        leadToken = token(changePin(token(login("lakshmi", "ab@123")), "ab@123", "cd@456"));
    }

    @Test
    void traineeSeesTheLineFromTheTopDownToThemselves() throws Exception {
        mockMvc.perform(get("/api/people/me/line").header("Authorization", auth(traineeToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chain[*].name", contains("Chitra CEO", "Hari Head", "Lakshmi Lead", "Tom Trainee")))
                .andExpect(jsonPath("$.chain[0].levelCode").value("10"))
                .andExpect(jsonPath("$.chain[3].designationName").value("Trainee"))
                .andExpect(jsonPath("$.reports.length()").value(0));
    }

    @Test
    void leadSeesTheirTeamAndTheirTeamsLines() throws Exception {
        mockMvc.perform(get("/api/people/me/line").header("Authorization", auth(leadToken)))
                .andExpect(jsonPath("$.reports[*].name", contains("Priya Peer", "Tom Trainee")));
        mockMvc.perform(get("/api/people/" + trainee + "/line").header("Authorization", auth(leadToken)))
                .andExpect(status().isOk());
        // but not the line of someone above them
        mockMvc.perform(get("/api/people/" + ceo + "/line").header("Authorization", auth(leadToken)))
                .andExpect(status().isForbidden());
        // nor can a trainee look at a colleague's line
        mockMvc.perform(get("/api/people/" + peer + "/line").header("Authorization", auth(traineeToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void orgChartIsForOwnersAndAdmins() throws Exception {
        mockMvc.perform(get("/api/people/org-chart").header("Authorization", auth(traineeToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/people/org-chart").header("Authorization", auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems("Chitra CEO", "Hari Head", "Lakshmi Lead", "Tom Trainee", "Priya Peer")));
    }

    @Test
    void colleaguesPhotosAreReadableByEveryone() throws Exception {
        // no photo yet: 404, not 403
        mockMvc.perform(get("/api/people/" + lead + "/photo").header("Authorization", auth(traineeToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/people/" + lead + "/photo")).andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private String create(String name, String userId, String role, Long brandId, Integer designationId,
                          String reportsToId) throws Exception {
        String json = """
                {"name":"%s","userId":"%s","password":"ab@123","role":"%s","brandId":%s,"designationId":%s,
                 "reportsToId":%s,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(name, userId, role, brandId, designationId, reportsToId);
        return String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/employees")
                        .header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())), "$.id"));
    }

    private static Integer level(String designations, String name) {
        List<Integer> ids = JsonPath.read(designations, "$[?(@.name == '" + name + "')].id");
        return ids.get(0);
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private ResultActions changePin(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
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
