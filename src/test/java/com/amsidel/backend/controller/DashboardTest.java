/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.service.MenuAccessService;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The dashboard: my own numbers, each part following its page's tick; meetings seen by those in them, added with a tick. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=dashboard")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MenuAccessService menuAccess;

    private String owner;
    private String anu;
    private String anuId;
    private String ben;
    private String benId;
    private String clara;
    private String otherId;
    private long dw;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.get(0);
        long ob = ((Number) JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"DB\",\"name\":\"Dash Brand\"}")), "$.id")).longValue();
        anuId = person("Anu A", "dash.anu", dw);
        anu = signIn("dash.anu");
        benId = person("Ben B", "dash.ben", dw);
        ben = signIn("dash.ben");
        person("Clara C", "dash.clara", dw);
        clara = signIn("dash.clara");
        otherId = person("Omar O", "dash.omar", ob);
        // Anu has the Add meetings tick, on top of what employees have
        List<String> everyone = checkpointsOfEmployees();
        everyone.add("500");
        menuAccess.save(AccessSubject.EMPLOYEE, anuId, everyone);
    }

    private List<String> checkpointsOfEmployees() {
        return new java.util.ArrayList<>(menuAccess.entries().stream()
                .filter(e -> e.subjectType() == AccessSubject.ROLE && e.subjectKey().equals("EMPLOYEE"))
                .findFirst().orElseThrow().checkpoints());
    }

    @Test
    void myOwnNumbersFromThePagesBehindThem() throws Exception {
        // a task closed and one open, 3 hours in the work log, and an open check-in
        task("DASH-1", "Windows", benId);
        String closed = task("DASH-2", "Doors", benId);
        json(put("/api/tasks/" + closed), owner, "{\"code\":\"DASH-2\",\"title\":\"Doors\",\"brandId\":%d,\"status\":\"CLOSED\",\"assigneeIds\":[%s]}".formatted(dw, benId))
                .andExpect(status().isOk());
        json(post("/api/worklog/entries"), ben, "{\"workDate\":\"%s\",\"taskId\":\"DASH-1\",\"hours\":3,\"description\":\"Fitting\"}".formatted(TODAY))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/attendance/check-in").param("workMode", "WFH").param("platform", "web").header("Authorization", auth(ben)))
                .andExpect(status().isCreated());

        part("summary", ben).andExpect(jsonPath("$.today").value(TODAY.toString())).andExpect(jsonPath("$.day.kind").isString());
        part("hours", ben).andExpect(jsonPath("$.today").value(3.0)).andExpect(jsonPath("$.week").value(3.0)).andExpect(jsonPath("$.days", hasSize(7)));
        part("tasks", ben).andExpect(jsonPath("$.completed").value(1)).andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.pendingTasks[0].code").value("DASH-1"));
        part("profile", ben).andExpect(jsonPath("$.percent").isNumber());
        part("leave", ben).andExpect(jsonPath("$.types").isArray());
        part("pay", ben).andExpect(jsonPath("$.salarySet").value(false));
        ResultActions r = part("attendance", ben).andExpect(jsonPath("$.checkedIn").value(true));
        // the working days of the week and month: every day but Sundays, and any holidays, from the day of joining
        int weekDays = ((Number) JsonPath.read(body(r), "$.week.days")).intValue();
        assertThat(weekDays).isBetween(1, 6);
        int monthDays = ((Number) JsonPath.read(body(r), "$.month.days")).intValue();
        long notSundays = TODAY.withDayOfMonth(1).datesUntil(YearMonth.from(TODAY).atEndOfMonth().plusDays(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SUNDAY).count();
        assertThat((long) monthDays).isLessThanOrEqualTo(notSundays).isPositive();
        assertThat(((Number) JsonPath.read(body(r), "$.week.attended")).intValue())
                .isEqualTo(TODAY.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : 1);
        // nobody else's: another person starts from nothing
        part("hours", clara).andExpect(jsonPath("$.today").value(0.0));
        part("tasks", clara).andExpect(jsonPath("$.pending").value(0));
        part("attendance", clara).andExpect(jsonPath("$.checkedIn").value(false));
    }

    @Test
    void aPartFollowsItsPagesTick() throws Exception {
        // only Check in: no work log, tasks, leave or pay
        String id = person("Tia T", "dash.tia", dw);
        String tia = signIn("dash.tia");
        menuAccess.save(AccessSubject.EMPLOYEE, id, List.of("1001"));
        part("attendance", tia).andExpect(jsonPath("$.checkedIn").value(false));
        for (String refused : List.of("hours", "tasks", "leave", "pay")) {
            assertThat(body(part(refused, tia))).as(refused).isEmpty();
        }
        part("mail", tia).andExpect(jsonPath("$", hasSize(0)));
        part("profile", tia).andExpect(jsonPath("$.percent").isNumber());
    }

    @Test
    void theOwnerHasADashboardToo() throws Exception {
        part("mail", owner).andExpect(jsonPath("$[0].kind").value("Staff email"))
                .andExpect(jsonPath("$[0].scope").value("All brands"))
                .andExpect(jsonPath("$[0].sentToday").value(0));
        part("summary", owner).andExpect(jsonPath("$.hasTeam").value(true));
    }

    @Test
    void meetingsAreSeenByThoseInThem() throws Exception {
        LocalDate day = TODAY.plusDays(3);
        String id = idOf(json(post("/api/meetings"), anu, meeting("Interview round 2", day, "14:30", "15:00", benId)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByName").value("Anu A"))
                .andExpect(jsonPath("$.people", hasSize(2))));

        // Anu added it, Ben is in it, Clara is not
        mockMvc.perform(get("/api/meetings").param("from", day.toString()).param("to", day.toString()).header("Authorization", auth(anu)))
                .andExpect(jsonPath("$.canAdd").value(true)).andExpect(jsonPath("$.meetings[0].canChange").value(true));
        mockMvc.perform(get("/api/meetings").param("from", day.toString()).param("to", day.toString()).header("Authorization", auth(ben)))
                .andExpect(jsonPath("$.canAdd").value(false))
                .andExpect(jsonPath("$.meetings[0].title").value("Interview round 2"))
                .andExpect(jsonPath("$.meetings[0].startTime").value("14:30:00"))
                .andExpect(jsonPath("$.meetings[0].canChange").value(false));
        mockMvc.perform(get("/api/meetings").param("from", day.toString()).param("to", day.toString()).header("Authorization", auth(clara)))
                .andExpect(jsonPath("$.meetings", hasSize(0)));

        // moved to today: it shows on the dashboard of those in it
        json(put("/api/meetings/" + id), anu, meeting("Interview round 2", TODAY, "23:30", null, benId)).andExpect(status().isOk());
        part("meetings-today", ben).andExpect(jsonPath("$[0].title").value("Interview round 2"));

        // taken out of it: Ben no longer sees it
        json(put("/api/meetings/" + id), anu, meeting("Interview round 2", TODAY, "23:30", null, null)).andExpect(status().isOk());
        part("meetings-today", ben).andExpect(jsonPath("$", hasSize(0)));

        // an owner can delete anyone's
        mockMvc.perform(delete("/api/meetings/" + id).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        part("meetings-today", anu).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void addingMeetingsNeedsTheTick() throws Exception {
        String body = meeting("Not allowed", TODAY.plusDays(1), "10:00", null, null);
        json(post("/api/meetings"), ben, body).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/meetings/people").header("Authorization", auth(ben))).andExpect(status().isForbidden());

        String id = idOf(json(post("/api/meetings"), owner, meeting("The owner's", TODAY.plusDays(1), "10:00", null, null)).andExpect(status().isCreated()));
        // the tick alone does not reach someone else's meeting
        json(put("/api/meetings/" + id), anu, meeting("Mine now", TODAY.plusDays(1), "10:00", null, null)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/meetings/" + id).header("Authorization", auth(anu))).andExpect(status().isForbidden());
        // and without the tick, not even that far
        mockMvc.perform(delete("/api/meetings/" + id).header("Authorization", auth(ben))).andExpect(status().isForbidden());
    }

    @Test
    void aMeetingIsChecked() throws Exception {
        json(post("/api/meetings"), anu, meeting("Yesterday", TODAY.minusDays(1), "10:00", null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A new meeting cannot be on a date that has passed"));
        json(post("/api/meetings"), anu, meeting("Backwards", TODAY.plusDays(1), "10:00", "09:00", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The end time must be after the start time"));
        // only people of your own brand; owners any
        json(post("/api/meetings"), anu, meeting("Other brand", TODAY.plusDays(1), "10:00", null, otherId)).andExpect(status().isForbidden());
        json(post("/api/meetings"), owner, meeting("Across brands", TODAY.plusDays(2), "10:00", null, otherId)).andExpect(status().isCreated());
        // the people list: own brand, without me
        String people = body(mockMvc.perform(get("/api/meetings/people").header("Authorization", auth(anu))).andExpect(status().isOk()));
        List<String> names = JsonPath.read(people, "$[*].name");
        assertThat(names).contains("Ben B", "Clara C").doesNotContain("Anu A", "Omar O");
        mockMvc.perform(get("/api/meetings").param("from", TODAY.toString()).param("to", TODAY.plusDays(90).toString()).header("Authorization", auth(anu)))
                .andExpect(status().isBadRequest());
    }

    private ResultActions part(String name, String token) throws Exception {
        return mockMvc.perform(get("/api/dashboard/" + name).header("Authorization", auth(token))).andExpect(status().isOk());
    }

    private String meeting(String title, LocalDate date, String start, String end, String personId) {
        return "{\"title\":\"%s\",\"date\":\"%s\",\"startTime\":\"%s\",\"endTime\":%s,\"location\":\"Room 1\",\"notes\":\"Bring the file\",\"peopleIds\":[%s]}"
                .formatted(title, date, start, end == null ? "null" : "\"" + end + "\"", personId == null ? "" : personId);
    }

    private String task(String code, String title, String assignee) throws Exception {
        return idOf(json(post("/api/tasks"), owner, "{\"code\":\"%s\",\"title\":\"%s\",\"brandId\":%d,\"assigneeIds\":[%s]}".formatted(code, title, dw, assignee))
                .andExpect(status().isCreated()));
    }

    private String person(String name, String userId, long brandId) throws Exception {
        return idOf(json(post("/api/employees"), owner, """
                {"name":"%s","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(name, userId, brandId)).andExpect(status().isCreated()));
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
