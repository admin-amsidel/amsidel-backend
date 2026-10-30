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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Project status updates: only people with the Project status checkpoint post them (with photos), everyone who sees
 * projects sees them; the newest sets the project's status; the customer's link works without sign-in, only with the
 * project code and its secret, shows no staff names, and stops when switched off or renewed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=projectstatus")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectStatusTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    /** A 1x1 PNG. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /** A photo of a realistic size (the column must hold more than a few bytes). */
    private static final byte[] BIG = java.util.Arrays.copyOf(PNG, 200_000);

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private String viewer;
    private String poster;
    private String poster2;
    private String project;
    private String code;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        long dw = brandIds.getFirst();
        viewer = person("ps.view", dw, "[\"11500\"]");
        poster = person("ps.post", dw, "[\"11501\"]");
        poster2 = person("ps.post2", dw, "[\"11501\"]");
        String customer = idOf(json(post("/api/trade/customers"), owner, "{\"brandId\":%d,\"name\":\"Varghese Villa\",\"phone\":\"9847033333\"}".formatted(dw)));
        String created = body(json(post("/api/trade/projects"), owner, """
                {"name":"Villa windows","customerId":%s,"startDate":"%s","endDate":"%s","estimatedAmount":50000}
                """.formatted(customer, TODAY, TODAY.plusDays(30))).andExpect(status().isCreated()));
        project = String.valueOf((Object) JsonPath.read(created, "$.id"));
        code = JsonPath.read(created, "$.code");
    }

    @Test
    void theProjectStatusesComeFromTheirTable() throws Exception {
        mockMvc.perform(get("/api/trade/project-statuses").header("Authorization", auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Queued", "Quotation issued", "Quotation approved", "Confirmed", "Started",
                        "Completed", "Cancelled")))
                .andExpect(jsonPath("$[?(@.code == 'COMPLETED')].phase").value(hasItem("DONE")));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        String customer = idOf(json(post("/api/trade/customers"), owner, "{\"brandId\":%d,\"name\":\"Stage Villa\",\"phone\":\"9847044444\"}".formatted(brandIds.getFirst())));
        String id = idOf(json(post("/api/trade/projects"), owner, """
                {"name":"Stage windows","customerId":%s,"startDate":"%s","status":"QUOTATION_ISSUED"}
                """.formatted(customer, TODAY)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("QUOTATION_ISSUED")));
        json(post("/api/trade/projects/" + id + "/status"), owner, "{\"status\":\"QUOTATION_APPROVED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUOTATION_APPROVED"))
                .andExpect(jsonPath("$.history[0].details", containsString("Quotation issued → Quotation approved")));
        json(post("/api/trade/projects/" + id + "/status"), owner, "{\"status\":\"ON_HOLD\"}").andExpect(status().isBadRequest());
        // a status update moves it on; Completed marks it done
        json(post("/api/trade/projects/" + id + "/updates"), poster, "{\"status\":\"CONFIRMED\",\"title\":\"Advance paid\"}")
                .andExpect(jsonPath("$.project.status").value("CONFIRMED"));
        json(post("/api/trade/projects/" + id + "/status"), owner, "{\"status\":\"COMPLETED\"}")
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
    }

    @Test
    void statusUpdatesWithPhotosAndTheCustomersLink() throws Exception {
        // seeing projects is not posting
        mockMvc.perform(get("/api/trade/projects/" + project + "/updates").header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canPost").value(false)).andExpect(jsonPath("$.updates").isEmpty());
        json(post("/api/trade/projects/" + project + "/updates"), viewer, "{\"status\":\"IN_PROGRESS\",\"title\":\"x\"}").andExpect(status().isForbidden());

        // posted with the date and time now (by default); the project moves to the update's stage
        String first = idOf(json(post("/api/trade/projects/" + project + "/updates"), poster,
                "{\"status\":\"IN_PROGRESS\",\"title\":\"Frames fixed\",\"description\":\"Ground floor frames are in.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canPost").value(true))
                .andExpect(jsonPath("$.updates[0].title").value("Frames fixed"))
                .andExpect(jsonPath("$.updates[0].createdByName").value("Post ps.post"))
                .andExpect(jsonPath("$.project.status").value("IN_PROGRESS")), "$.updates[0].id");
        mockMvc.perform(get("/api/trade/projects/" + project).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.history[*].details", hasItem(containsString("Posted “Frames fixed”"))));

        // photos: JPEG, PNG or WebP only
        mockMvc.perform(multipart("/api/trade/project-updates/" + first + "/photos")
                        .file(new MockMultipartFile("files", "a.png", "image/png", PNG))
                        .file(new MockMultipartFile("files", "b.png", "image/png", BIG))
                        .header("Authorization", auth(poster)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updates[0].photoIds.length()").value(2));
        mockMvc.perform(multipart("/api/trade/project-updates/" + first + "/photos")
                        .file(new MockMultipartFile("files", "c.txt", "text/plain", "hello".getBytes()))
                        .header("Authorization", auth(poster)))
                .andExpect(status().isBadRequest());

        // an older update (dated yesterday) does not change the project's status; a date in the future is refused
        String yesterday = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        json(post("/api/trade/projects/" + project + "/updates"), poster,
                "{\"status\":\"QUEUED\",\"title\":\"Measured the openings\",\"happenedAt\":\"%s\"}".formatted(yesterday))
                .andExpect(jsonPath("$.updates.length()").value(2))
                .andExpect(jsonPath("$.updates[0].title").value("Frames fixed"))
                .andExpect(jsonPath("$.updates[1].title").value("Measured the openings"))
                .andExpect(jsonPath("$.project.status").value("IN_PROGRESS"));
        json(post("/api/trade/projects/" + project + "/updates"), poster,
                "{\"status\":\"QUEUED\",\"title\":\"Later\",\"happenedAt\":\"%s\"}".formatted(Instant.now().plus(2, ChronoUnit.DAYS)))
                .andExpect(status().isBadRequest());

        // changed only by the person who posted it, or an owner or admin
        json(put("/api/trade/project-updates/" + first), poster2, "{\"status\":\"IN_PROGRESS\",\"title\":\"Mine now\"}").andExpect(status().isForbidden());
        json(put("/api/trade/project-updates/" + first), owner, "{\"status\":\"IN_PROGRESS\",\"title\":\"Ground floor frames fixed\"}")
                .andExpect(jsonPath("$.updates[0].title").value("Ground floor frames fixed"))
                .andExpect(jsonPath("$.updates[0].updatedByName").value("Amsidel Owner"));

        // the customer's link: no sign-in, only with the code and the secret, no staff names
        mockMvc.perform(get("/api/public/project-status/" + code + "/whatever-not-shared-yet")).andExpect(status().isNotFound());
        String path = JsonPath.read(body(mockMvc.perform(post("/api/trade/projects/" + project + "/share").header("Authorization", auth(poster)))
                .andExpect(jsonPath("$.sharePath", startsWith("/track/" + code + "/")))), "$.sharePath");
        String secret = path.substring(path.lastIndexOf('/') + 1);
        String pub = body(mockMvc.perform(get("/api/public/project-status/" + code + "/" + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("Villa windows"))
                .andExpect(jsonPath("$.customerName").value("Varghese Villa"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.updates.length()").value(2))
                .andExpect(jsonPath("$.updates[0].photoIds.length()").value(2)));
        assertThat(pub).doesNotContain("Post ps.post").doesNotContain("Amsidel Owner").doesNotContain("50000").doesNotContain("estimated");
        int photo = JsonPath.read(pub, "$.updates[0].photoIds[0]");
        mockMvc.perform(get("/api/public/project-status/" + code + "/" + secret + "/photos/" + photo))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mockMvc.perform(get("/api/public/project-status/OTHER-CODE/" + secret)).andExpect(status().isNotFound());
        // the open link is read only: nothing else of the portal opens with it
        mockMvc.perform(get("/api/trade/projects/" + project + "/updates")).andExpect(status().isUnauthorized());

        // a new link replaces the old one; switched off, it stops
        String renewed = JsonPath.read(body(mockMvc.perform(post("/api/trade/projects/" + project + "/share").header("Authorization", auth(poster)))), "$.sharePath");
        assertThat(renewed).isNotEqualTo(path);
        mockMvc.perform(get("/api/public/project-status/" + code + "/" + secret)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/trade/projects/" + project + "/share").header("Authorization", auth(poster)))
                .andExpect(jsonPath("$.sharePath").doesNotExist());
        mockMvc.perform(get("/api/public/project-status/" + code + "/" + renewed.substring(renewed.lastIndexOf('/') + 1))).andExpect(status().isNotFound());

        // the Project status page; a photo removed; an update removed
        mockMvc.perform(get("/api/trade/project-status").header("Authorization", auth(poster)))
                .andExpect(jsonPath("$[?(@.code == '%s')].updates".formatted(code)).value(hasItem(2)));
        json(delete("/api/trade/project-update-photos/" + photo), poster, "").andExpect(jsonPath("$.updates[0].photoIds.length()").value(1));
        json(delete("/api/trade/project-updates/" + first), poster, "").andExpect(jsonPath("$.updates.length()").value(1));
    }

    // ---- helpers ----

    private String person(String userId, long brand, String checkpoints) throws Exception {
        String id = idOf(json(post("/api/employees"), owner,
                "{\"name\":\"Post %s\",\"userId\":\"%s\",\"password\":\"ab@123\",\"role\":\"EMPLOYEE\",\"brandId\":%d,\"phone\":\"+91 98470 00000\",\"joinedDate\":\"2026-01-05\"}"
                        .formatted(userId, userId, brand)).andExpect(status().isCreated()));
        json(put("/api/menu-access/EMPLOYEE/" + id), owner, "{\"checkpoints\":" + checkpoints + "}").andExpect(status().isOk());
        return token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(body));
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

    private static String idOf(ResultActions result) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), "$.id"));
    }

    private static String idOf(ResultActions result, String path) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), path));
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
