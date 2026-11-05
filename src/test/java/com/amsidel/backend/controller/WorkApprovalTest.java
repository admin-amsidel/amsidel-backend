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
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Attendance and work log entries wait for the person's approver, and only count once approved. The approver: the Approver
 * set on the employee, else the person they report to; with neither, an entry counts at once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=workapprovals")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkApprovalTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    /** The nearest past day that is not a Sunday: entries can be logged on it. */
    private static final LocalDate PAST = TODAY.minusDays(1).getDayOfWeek() == DayOfWeek.SUNDAY ? TODAY.minusDays(2) : TODAY.minusDays(1);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MenuAccessService menuAccess;

    private String owner;
    private long dw;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.get(0);
    }

    /** {id, token} of someone new. */
    private String[] person(String reportsTo, String approver) throws Exception {
        String userId = "wa." + (++seq);
        String id = idOf(json(post("/api/employees"), owner, """
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"reportsToId":%s,"approverId":%s,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(seq, userId, dw, reportsTo, approver)).andExpect(status().isCreated()));
        return new String[]{id, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    @Test
    void theApproverIsKeptOnTheEmployeeAndNobodyApprovesThemselves() throws Exception {
        String[] boss = person(null, null);
        String[] emp = person(boss[0], boss[0]);
        mockMvc.perform(get("/api/employees/" + emp[0]).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.approverId").value(Integer.valueOf(boss[0])))
                .andExpect(jsonPath("$.approverName").isString());
        json(put("/api/employees/" + boss[0]), owner, """
                {"name":"Boss","userId":"wa.1x","role":"EMPLOYEE","brandId":%d,"approverId":%s,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(dw, boss[0])).andExpect(status().isBadRequest());
    }

    @Test
    void attendanceWaitsThenCountsOnlyOnceApproved() throws Exception {
        String[] approver = person(null, null);
        String[] emp = person(null, approver[0]);
        checkIn(emp[1]).andExpect(jsonPath("$.approval").value("PENDING"));
        // still checked in today: nothing to approve yet
        String id = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(get("/api/attendance/today").header("Authorization", auth(emp[1])))), "$.day.entries[0].id"));
        queue(approver[1]).andExpect(jsonPath("$.attendance[0].decidable").value(false));
        decide(approver[1], "[" + id + "]", "[]", true).andExpect(status().isConflict());

        checkOut(emp[1]);
        queue(approver[1]).andExpect(jsonPath("$.attendance", hasSize(1))).andExpect(jsonPath("$.attendance[0].decidable").value(true));
        // waiting: seen, not counted
        dash(emp[1], "attendance").andExpect(jsonPath("$.waiting").value(true)).andExpect(jsonPath("$.week.attended").value(0));
        mockMvc.perform(get("/api/attendance/today").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.day.counted").value(false));

        decide(approver[1], "[" + id + "]", "[]", true).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        queue(approver[1]).andExpect(jsonPath("$.attendance", hasSize(0)));
        mockMvc.perform(get("/api/attendance/today").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.day.counted").value(true)).andExpect(jsonPath("$.day.entries[0].approval").value("APPROVED"));
        if (TODAY.getDayOfWeek() != DayOfWeek.SUNDAY) {
            dash(emp[1], "attendance").andExpect(jsonPath("$.week.attended").value(1));
        }
    }

    @Test
    void workLogHoursWaitAndAChangeGoesBackToTheApprover() throws Exception {
        String[] approver = person(null, null);
        String[] emp = person(null, approver[0]);
        String code = task(emp[0]);
        String id = idOf(log(emp[1], PAST, code, "4").andExpect(status().isCreated()).andExpect(jsonPath("$.approval").value("PENDING")));
        logged(code).andExpect(jsonPath("$[?(@.code == '" + code + "')].loggedHours").value(org.hamcrest.Matchers.hasItem(0)));
        queue(approver[1]).andExpect(jsonPath("$.workLogs[0].taskId").value(code)).andExpect(jsonPath("$.workLogs[0].hours").value(4.0));

        decide(approver[1], "[]", "[" + id + "]", true).andExpect(jsonPath("$.count").value(1));
        logged(code).andExpect(jsonPath("$[?(@.code == '" + code + "')].loggedHours").value(org.hamcrest.Matchers.hasItem(4.0)));

        // changed after approval: waits again, and the hours stop counting
        json(put("/api/worklog/entries/" + id), emp[1], "{\"workDate\":\"%s\",\"taskId\":\"%s\",\"hours\":5,\"description\":\"More\"}".formatted(PAST, code))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approval").value("PENDING"));
        logged(code).andExpect(jsonPath("$[?(@.code == '" + code + "')].loggedHours").value(org.hamcrest.Matchers.hasItem(0)));

        // rejected, with a reason the person sees: never counts, and the day's 24 hours are free again
        json(post("/api/work-approvals/decide"), approver[1], "{\"workLogIds\":[%s],\"approve\":false,\"note\":\"Wrong task\"}".formatted(id)).andExpect(status().isOk());
        logged(code).andExpect(jsonPath("$[?(@.code == '" + code + "')].loggedHours").value(org.hamcrest.Matchers.hasItem(0)));
        mockMvc.perform(get("/api/worklog").param("from", PAST.toString()).param("to", PAST.toString()).header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.entries[0].approval").value("REJECTED")).andExpect(jsonPath("$.entries[0].decisionNote").value("Wrong task"));
        log(emp[1], PAST, code, "24").andExpect(status().isCreated());
    }

    @Test
    void theReportingPersonApprovesWhenNoApproverIsSetAndNobodyMeansAtOnce() throws Exception {
        String[] boss = person(null, null);
        String[] emp = person(boss[0], null);
        String code = task(emp[0]);
        log(emp[1], PAST, code, "1").andExpect(jsonPath("$.approval").value("PENDING"));
        queue(boss[1]).andExpect(jsonPath("$.workLogs", hasSize(1)));

        // nobody above them: it counts at once, and is in nobody's queue
        String bossCode = task(boss[0]);
        log(boss[1], PAST, bossCode, "1").andExpect(jsonPath("$.approval").value("APPROVED"));
        logged(bossCode).andExpect(jsonPath("$[?(@.code == '" + bossCode + "')].loggedHours").value(org.hamcrest.Matchers.hasItem(1.0)));
    }

    @Test
    void onlyTheirApproverOrAnOwnerDecides() throws Exception {
        String[] approver = person(null, null);
        String[] boss = person(null, null);
        String[] peer = person(null, null);
        // the approver goes first: the person they report to has no say
        String[] emp = person(boss[0], approver[0]);
        String code = task(emp[0]);
        String id = idOf(log(emp[1], PAST, code, "1"));

        queue(boss[1]).andExpect(jsonPath("$.workLogs", hasSize(0)));
        decide(boss[1], "[]", "[" + id + "]", true).andExpect(status().isForbidden());
        decide(emp[1], "[]", "[" + id + "]", true).andExpect(status().isForbidden());
        // someone with no one to approve has no queue at all
        mockMvc.perform(get("/api/work-approvals").header("Authorization", auth(peer[1]))).andExpect(status().isForbidden());

        decide(owner, "[]", "[" + id + "]", true).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        // already decided: left as it is
        decide(approver[1], "[]", "[" + id + "]", false).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void autoApproveCoversAttendanceTasksAndLeaveOfTheirPeople() throws Exception {
        // on the approver, and on the person they report to when no approver is set
        for (boolean asApprover : List.of(true, false)) {
            String userId = "wa.auto" + (++seq);
            String boss = idOf(json(post("/api/employees"), owner, """
                    {"name":"Auto Boss","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"autoApproveReports":true,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                    """.formatted(userId, dw)).andExpect(status().isCreated()).andExpect(jsonPath("$.autoApproveReports").value(true)));
            String[] emp = asApprover ? person(null, boss) : person(boss, null);
            String code = task(emp[0]);
            log(emp[1], PAST, code, "3").andExpect(status().isCreated()).andExpect(jsonPath("$.approval").value("APPROVED"));
            checkIn(emp[1]).andExpect(jsonPath("$.approval").value("APPROVED"));
            // leave too
            String types = body(mockMvc.perform(get("/api/leave/types").header("Authorization", auth(owner))));
            List<Integer> cl = JsonPath.read(types, "$[?(@.code == 'CL')].id");
            LocalDate next = TODAY.plusDays(1).getDayOfWeek() == DayOfWeek.SUNDAY ? TODAY.plusDays(2) : TODAY.plusDays(1);
            json(post("/api/leave/me/requests"), emp[1], "{\"leaveTypeId\":%d,\"fromDate\":\"%s\",\"toDate\":\"%s\",\"halfDay\":false,\"reason\":\"Family\"}".formatted(cl.get(0), next, next))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPROVED"));
        }
    }

    @Test
    void anApproverWhoLeadsNobodyStillGetsTheQueue() throws Exception {
        String[] approver = person(null, null);
        mockMvc.perform(get("/api/work-approvals").header("Authorization", auth(approver[1]))).andExpect(status().isForbidden());
        person(null, approver[0]);
        mockMvc.perform(get("/api/work-approvals").header("Authorization", auth(approver[1]))).andExpect(status().isOk());
    }

    @Test
    void theDeficiencyComesFromTheWorkHourRule() throws Exception {
        String[] emp = person(null, null);
        // no check-in on a past working day: all of it is short
        String json = body(mockMvc.perform(get("/api/attendance/shortfall").param("from", PAST.toString()).param("to", PAST.toString()).header("Authorization", auth(emp[1])))
                .andExpect(status().isOk()));
        int required = JsonPath.read(json, "$.requiredMinutes");
        assertThat(((Number) JsonPath.read(json, "$.dailyMinHours")).doubleValue() * 60).isEqualTo(required);
        assertThat((Integer) JsonPath.read(json, "$.shortMinutes")).isEqualTo(required);
        assertThat((Integer) JsonPath.read(json, "$.workedMinutes")).isZero();

        // someone else's: only for those above them
        String[] other = person(null, null);
        mockMvc.perform(get("/api/attendance/shortfall").param("employeeId", emp[0]).param("from", PAST.toString()).param("to", PAST.toString()).header("Authorization", auth(other[1])))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/attendance/shortfall").param("employeeId", emp[0]).param("from", PAST.toString()).param("to", PAST.toString()).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.shortMinutes").value(required));
        // today is listed but not held against them, as it is not over
        mockMvc.perform(get("/api/attendance/shortfall").param("from", TODAY.toString()).param("to", TODAY.toString()).header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.shortMinutes").value(0)).andExpect(jsonPath("$.requiredMinutes").value(0));
    }

    @Test
    void theCalendarGetsCheckedInTimeAndTheCheckFollowsTheTick() throws Exception {
        String[] approver = person(null, null);
        String[] emp = person(null, approver[0]);
        checkIn(emp[1]);
        checkOut(emp[1]);
        month(emp[1]).andExpect(jsonPath("$.attendance", hasSize(1))).andExpect(jsonPath("$.attendance[0].date").value(TODAY.toString()))
                .andExpect(jsonPath("$.attendance[0].open").value(false)).andExpect(jsonPath("$.attendance[0].waiting").value(true))
                .andExpect(jsonPath("$.hoursCheck").value(false));

        // given to the person itself; admins and owners do not get it through "every menu"
        menuAccess.save(AccessSubject.EMPLOYEE, emp[0], List.of("1001", "3001", "3005"));
        month(emp[1]).andExpect(jsonPath("$.hoursCheck").value(true));
        // whoever looks at their calendar sees it the same way
        mockMvc.perform(get("/api/worklog").param("employeeId", emp[0]).param("from", TODAY.toString()).param("to", TODAY.toString()).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.hoursCheck").value(true));
        mockMvc.perform(get("/api/worklog").param("from", TODAY.toString()).param("to", TODAY.toString()).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.hoursCheck").value(false));
    }

    private ResultActions month(String token) throws Exception {
        return mockMvc.perform(get("/api/worklog").param("from", TODAY.toString()).param("to", TODAY.toString()).header("Authorization", auth(token))).andExpect(status().isOk());
    }

    private ResultActions dash(String token, String part) throws Exception {
        return mockMvc.perform(get("/api/dashboard/" + part).header("Authorization", auth(token))).andExpect(status().isOk());
    }

    private ResultActions queue(String token) throws Exception {
        return mockMvc.perform(get("/api/work-approvals").header("Authorization", auth(token))).andExpect(status().isOk());
    }

    private ResultActions decide(String token, String attendance, String logs, boolean approve) throws Exception {
        return json(post("/api/work-approvals/decide"), token, "{\"attendanceIds\":%s,\"workLogIds\":%s,\"approve\":%s}".formatted(attendance, logs, approve));
    }

    private ResultActions checkIn(String token) throws Exception {
        return mockMvc.perform(multipart("/api/attendance/check-in").param("workMode", "WFH").param("platform", "web").header("Authorization", auth(token)))
                .andExpect(status().isCreated());
    }

    private void checkOut(String token) throws Exception {
        json(post("/api/attendance/check-out"), token, "{}").andExpect(status().isOk());
    }

    private String task(String assignee) throws Exception {
        String code = "WA-" + (++seq);
        json(post("/api/tasks"), owner, "{\"code\":\"%s\",\"title\":\"Task\",\"brandId\":%d,\"assigneeIds\":[%s]}".formatted(code, dw, assignee)).andExpect(status().isCreated());
        return code;
    }

    private ResultActions log(String token, LocalDate day, String code, String hours) throws Exception {
        return json(post("/api/worklog/entries"), token, "{\"workDate\":\"%s\",\"taskId\":\"%s\",\"hours\":%s,\"description\":\"Work\"}".formatted(day, code, hours));
    }

    private ResultActions logged(String code) throws Exception {
        return mockMvc.perform(get("/api/tasks").header("Authorization", auth(owner))).andExpect(status().isOk());
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
