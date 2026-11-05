/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.repository.NotificationClearRepository;
import com.amsidel.backend.service.MenuAccessService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The notification bell, with the clock moved by hand: 5 minutes before a meeting, while it runs, once it has closed. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=notifications")
@Import(NotificationTest.FixedClock.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(ZONE);

    /** A clock the test sets. */
    static final class HandClock extends Clock {
        private Instant at = Instant.now();

        void set(LocalDate day, String time) {
            at = day.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return at;
        }
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        HandClock handClock() {
            return new HandClock();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MenuAccessService menuAccess;
    @Autowired
    private HandClock clock;
    @Autowired
    private NotificationClearRepository clears;

    private String owner;
    private String anu;
    private String anuId;
    private String ben;
    private String benId;
    private String none;
    private long dw;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.get(0);
        anuId = person("Anu A", "note.anu");
        anu = signIn("note.anu");
        benId = person("Ben B", "note.ben");
        ben = signIn("note.ben");
        person("Nia N", "note.nia");
        none = signIn("note.nia");
        // the tick, for Anu and Ben; Nia keeps the employee's menus, which do not have it
        menuAccess.save(AccessSubject.EMPLOYEE, anuId, List.of("600"));
        menuAccess.save(AccessSubject.EMPLOYEE, benId, List.of("600"));
    }

    @BeforeEach
    void clearTheBoard() throws Exception {
        // each test has its own meetings: those of the last one are deleted
        clock.set(TODAY, "00:00");
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/meetings").param("from", TODAY.toString()).param("to", TODAY.toString()).header("Authorization", auth(owner)))), "$.meetings[*].id");
        for (Integer id : ids) {
            mockMvc.perform(delete("/api/meetings/" + id).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        }
    }

    @Test
    void itNeedsTheTick() throws Exception {
        mockMvc.perform(get("/api/notifications").header("Authorization", auth(none))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/notifications").header("Authorization", auth(none))).andExpect(status().isForbidden());
        // the check's timing comes from the properties
        feed(anu).andExpect(jsonPath("$.pollMinutes").value(5));
        feed(owner).andExpect(status().isOk());
    }

    @Test
    void fiveMinutesBeforeThenWhileItRunsThenClosed() throws Exception {
        meeting("Design review", "12:00", "13:00", anuId);

        clock.set(TODAY, "11:54");
        feed(anu).andExpect(jsonPath("$.items", hasSize(0)));
        clock.set(TODAY, "11:55");
        feed(anu).andExpect(jsonPath("$.items[0].kind").value("STARTING")).andExpect(jsonPath("$.items[0].started").value(false))
                .andExpect(jsonPath("$.items[0].title").value("Design review")).andExpect(jsonPath("$.items[0].location").value("Room 1"));
        clock.set(TODAY, "12:30");
        feed(anu).andExpect(jsonPath("$.items[0].kind").value("STARTING")).andExpect(jsonPath("$.items[0].started").value(true));
        clock.set(TODAY, "13:00");
        feed(anu).andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].kind").value("CLOSED"));
        // someone not in it hears nothing
        feed(ben).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void aMeetingWithNoEndTimeClosesAfterTheDefaultLength() throws Exception {
        meeting("Quick call", "12:00", null, anuId);
        clock.set(TODAY, "12:59");
        feed(anu).andExpect(jsonPath("$.items[0].kind").value("STARTING"));
        clock.set(TODAY, "13:00");
        feed(anu).andExpect(jsonPath("$.items[0].kind").value("CLOSED"));
    }

    @Test
    void clearedStaysClearedAndOnlyForMe() throws Exception {
        String id = meeting("Site visit", "12:00", "13:00", anuId + "," + benId);
        clock.set(TODAY, "11:58");
        clear(anu, id, "STARTING").andExpect(status().isNoContent());
        clear(anu, id, "STARTING").andExpect(status().isNoContent());
        feed(anu).andExpect(jsonPath("$.items", hasSize(0)));
        // the next check does not bring it back, and Ben still has his
        clock.set(TODAY, "12:05");
        feed(anu).andExpect(jsonPath("$.items", hasSize(0)));
        feed(ben).andExpect(jsonPath("$.items", hasSize(1)));
        // closing is news again, and clearing it is the end of it
        clock.set(TODAY, "13:05");
        feed(anu).andExpect(jsonPath("$.items[0].kind").value("CLOSED"));
        clear(anu, id, "CLOSED").andExpect(status().isNoContent());
        feed(anu).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void clearAllAndOnlyMyOwnMeetings() throws Exception {
        String theirs = meeting("Ben only", "12:00", "13:00", benId);
        meeting("One", "12:00", "13:00", anuId);
        meeting("Two", "12:10", "13:00", anuId);
        clock.set(TODAY, "12:05");
        feed(anu).andExpect(jsonPath("$.items", hasSize(2)));
        clear(anu, theirs, "STARTING").andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/notifications").header("Authorization", auth(anu))).andExpect(status().isNoContent());
        feed(anu).andExpect(jsonPath("$.items", hasSize(0)));
        feed(ben).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void movingOrDeletingAMeetingLeavesNoMarkBehind() throws Exception {
        String id = meeting("Moved", "12:00", "13:00", anuId);
        clock.set(TODAY, "11:58");
        clear(anu, id, "STARTING").andExpect(status().isNoContent());
        assertThat(clears.findByEmployeeId(Long.valueOf(anuId))).isNotEmpty();
        // moved: it is news again
        json(put("/api/meetings/" + id), owner, meetingJson("Moved", TODAY, "12:30", "13:00", anuId)).andExpect(status().isOk());
        clock.set(TODAY, "12:26");
        feed(anu).andExpect(jsonPath("$.items", hasSize(1)));
        clear(anu, id, "STARTING").andExpect(status().isNoContent());
        // deleted: nothing stays in the database
        mockMvc.perform(delete("/api/meetings/" + id).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        assertThat(clears.findByEmployeeId(Long.valueOf(anuId))).isEmpty();
    }

    @Test
    void anUnclearedClosedOneWaitsADayThenGoes() throws Exception {
        String id = meeting("Yesterday's", "12:00", "13:00", benId);
        clock.set(TODAY.plusDays(1), "09:00");
        feed(ben).andExpect(jsonPath("$.items[0].kind").value("CLOSED"));
        clear(ben, id, "CLOSED").andExpect(status().isNoContent());
        assertThat(clears.findByEmployeeId(Long.valueOf(benId))).isNotEmpty();
        // two days on it is too old to show, and the next clear takes its mark away
        clock.set(TODAY.plusDays(2), "09:00");
        feed(ben).andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(delete("/api/notifications").header("Authorization", auth(ben))).andExpect(status().isNoContent());
        assertThat(clears.findByEmployeeId(Long.valueOf(benId))).isEmpty();
    }

    private String meeting(String title, String start, String end, String people) throws Exception {
        return idOf(json(post("/api/meetings"), owner, meetingJson(title, TODAY, start, end, people)).andExpect(status().isCreated()));
    }

    private static String meetingJson(String title, LocalDate date, String start, String end, String people) {
        return "{\"title\":\"%s\",\"date\":\"%s\",\"startTime\":\"%s\",\"endTime\":%s,\"location\":\"Room 1\",\"peopleIds\":[%s]}"
                .formatted(title, date, start, end == null ? "null" : "\"" + end + "\"", people);
    }

    private ResultActions feed(String token) throws Exception {
        return mockMvc.perform(get("/api/notifications").header("Authorization", auth(token)));
    }

    private ResultActions clear(String token, String meetingId, String kind) throws Exception {
        return mockMvc.perform(delete("/api/notifications/" + meetingId + "/" + kind).header("Authorization", auth(token)));
    }

    private String person(String name, String userId) throws Exception {
        return idOf(json(post("/api/employees"), owner, """
                {"name":"%s","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(name, userId, dw)).andExpect(status().isCreated()));
    }

    private String signIn(String userId) throws Exception {
        return token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token, String content) throws Exception {
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

    private static String idOf(ResultActions result) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), "$.id"));
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
