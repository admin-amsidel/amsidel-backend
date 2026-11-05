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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A brand's time zone, India by default. The two zones used here are a day apart at every moment, so "today" really differs
 * between them whenever this runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=timezone")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimeZoneTest {

    private static final String AHEAD = "Pacific/Kiritimati";
    private static final String BEHIND = "Pacific/Pago_Pago";

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
    }

    private int brand() throws Exception {
        String code = "TZ" + (++seq);
        return JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"" + code + "\",\"name\":\"Zone " + code + "\"}").andExpect(status().isCreated())), "$.id");
    }

    private ResultActions setZone(int brand, String zone) throws Exception {
        String field = zone == null ? "" : ",\"timeZone\":\"" + zone + "\"";
        return json(put("/api/brands/" + brand), owner, "{\"code\":\"TZ" + brand + "\",\"name\":\"Zone " + brand + "\"" + field + "}");
    }

    /** Someone of that brand who may add meetings and has the bell. */
    private String person(int brand) throws Exception {
        String userId = "zone." + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(json(post("/api/employees"), owner, """
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, brand)).andExpect(status().isCreated())), "$.id"));
        json(put("/api/menu-access/EMPLOYEE/" + id), owner, "{\"checkpoints\":[\"500\",\"600\"]}").andExpect(status().isOk());
        return token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
    }

    private ResultActions access(String token) throws Exception {
        return mockMvc.perform(get("/api/auth/access").header("Authorization", auth(token))).andExpect(status().isOk());
    }

    private ResultActions meeting(String token, String title, LocalDate date) throws Exception {
        return json(post("/api/meetings"), token, """
                {"title":"%s","date":"%s","startTime":"00:00","endTime":null,"location":null,"notes":null,"peopleIds":[]}
                """.formatted(title, date));
    }

    @Test
    void indiaIsTheDefaultAndABrandCanHaveItsOwn() throws Exception {
        int brand = brand();
        String p = person(brand);
        access(p).andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"));
        access(owner).andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"));

        setZone(brand, "Asia/Dubai").andExpect(status().isOk()).andExpect(jsonPath("$.timeZone").value("Asia/Dubai"));
        access(p).andExpect(jsonPath("$.timeZone").value("Asia/Dubai"));
        // someone with no brand stays on the default
        access(owner).andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"));
        // another brand is not touched
        access(person(brand())).andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"));

        // saving the brand without a zone keeps it
        setZone(brand, null).andExpect(status().isOk()).andExpect(jsonPath("$.timeZone").value("Asia/Dubai"));
    }

    @Test
    void onlyARealZoneIsTaken() throws Exception {
        int brand = brand();
        setZone(brand, "Asia/Dubai").andExpect(status().isOk());
        setZone(brand, "Mars/Olympus").andExpect(status().isBadRequest());
        // the browser would not understand an offset
        setZone(brand, "+05:30").andExpect(status().isBadRequest());
        access(person(brand)).andExpect(jsonPath("$.timeZone").value("Asia/Dubai"));
    }

    @Test
    void theCalendarsTodayIsTheBrands() throws Exception {
        int brand = brand();
        String p = person(brand);
        for (String zone : new String[]{AHEAD, BEHIND}) {
            setZone(brand, zone).andExpect(status().isOk());
            LocalDate today = LocalDate.now(ZoneId.of(zone));
            mockMvc.perform(get("/api/meetings").param("from", today.minusDays(1).toString()).param("to", today.plusDays(1).toString()).header("Authorization", auth(p)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.today").value(today.toString())).andExpect(jsonPath("$.zone").value(zone));
        }
    }

    @Test
    void aDateThatHasPassedIsJudgedByTheBrandsZone() throws Exception {
        int brand = brand();
        String p = person(brand);
        // yesterday for the zone ahead, today or later for the zone behind
        LocalDate date = LocalDate.now(ZoneId.of(AHEAD)).minusDays(1);
        setZone(brand, AHEAD).andExpect(status().isOk());
        meeting(p, "Too late", date).andExpect(status().isBadRequest());
        setZone(brand, BEHIND).andExpect(status().isOk());
        meeting(p, "In time", date).andExpect(status().isCreated());
    }

    @Test
    void theBellGoesByTheBrandsZone() throws Exception {
        int brand = brand();
        String p = person(brand);
        // today for the zone ahead, still in the future for the zone behind
        setZone(brand, BEHIND).andExpect(status().isOk());
        meeting(p, "Midnight meeting", LocalDate.now(ZoneId.of(AHEAD))).andExpect(status().isCreated());
        mockMvc.perform(get("/api/notifications").header("Authorization", auth(p))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].title", not(hasItem("Midnight meeting"))));

        setZone(brand, AHEAD).andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications").header("Authorization", auth(p))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].title", hasItem("Midnight meeting")));
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
