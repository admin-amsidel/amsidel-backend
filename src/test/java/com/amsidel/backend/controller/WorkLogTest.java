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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Work log: a Team Lead with a Trainee reporting to her, and an unrelated colleague. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=worklog")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkLogTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private String lead;
    private String trainee;
    private String other;
    private String traineeId;
    private long dw;
    private String levels;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.get(0);
        levels = body(mockMvc.perform(get("/api/designations").header("Authorization", auth(owner))));
        String leadId = create("Lakshmi Lead", "wl.lead", "MANAGER", "Team Lead", null);
        traineeId = create("Tom Trainee", "wl.tom", "EMPLOYEE", "Trainee", leadId);
        create("Olivia Other", "wl.other", "EMPLOYEE", "Team Member", null);
        lead = token(changePin(token(login("wl.lead", "ab@123")), "ab@123", "cd@456"));
        trainee = token(changePin(token(login("wl.tom", "ab@123")), "ab@123", "cd@456"));
        other = token(changePin(token(login("wl.other", "ab@123")), "ab@123", "cd@456"));
        // the tasks the tests below log against, all assigned to the trainee
        for (String code : List.of("MSC-101", "MSC-102", "MSC-1", "MSC-2", "MSC-201")) {
            task(owner, code, "Task " + code, "OPEN", traineeId).andExpect(status().isCreated());
        }
    }

    @Test
    void severalTasksADayInTheOrderAdded() throws Exception {
        LocalDate day = TODAY.minusDays(3);
        log(trainee, day, "msc-101", "3.5", "Window frame quote").andExpect(status().isCreated())
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.taskId").value("MSC-101"));
        log(trainee, day, "MSC-102", "2", "Site visit report").andExpect(jsonPath("$.sequence").value(2));

        month(trainee, null, day, day)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.joinedDate").value("2026-01-01"))
                .andExpect(jsonPath("$.entries[*].taskId", contains("MSC-101", "MSC-102")));
        // a Team Member has no rule of its own: the brand's default applies
        month(other, null, day, day)
                .andExpect(jsonPath("$.target.dailyMinHours").value(9))
                .andExpect(jsonPath("$.target.source").value("DW default"))
                .andExpect(jsonPath("$.target.weeklyMinHours").value(54))
                .andExpect(jsonPath("$.target.halfDayBelow").value(4.5));
    }

    @Test
    void workLogExportsToExcel() throws Exception {
        task(owner, "MSC-301", "Export check", "OPEN", traineeId).andExpect(status().isCreated());
        LocalDate day = TODAY.minusDays(40);
        log(trainee, day, "MSC-301", "4", "Glass order follow-up").andExpect(status().isCreated());
        approveAll();

        byte[] file = mockMvc.perform(get("/api/worklog/export").header("Authorization", auth(lead))
                        .param("employeeId", traineeId).param("from", day.toString()).param("to", day.plusDays(6).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(XlsxRead.sheets(file)).containsExactly("Summary", "Daily", "Entries");
        String shown = day.format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH));
        List<String> entry = XlsxRead.rowWith(file, "Entries", "Tom Trainee", shown, "MSC-301");
        org.assertj.core.api.Assertions.assertThat(entry).contains("Export check", "4.00", "Glass order follow-up");
        List<String> daily = XlsxRead.rowWith(file, "Daily", "Tom Trainee", shown);
        org.assertj.core.api.Assertions.assertThat(daily.get(4)).isEqualTo("4.00");
        org.assertj.core.api.Assertions.assertThat(XlsxRead.rows(file, "Daily").stream().filter(r -> r.contains("Tom Trainee")).count())
                .isEqualTo(7);
        org.assertj.core.api.Assertions.assertThat(XlsxRead.rowWith(file, "Summary", "Tom Trainee").get(9)).isEqualTo("4.00");

        mockMvc.perform(get("/api/worklog/export").header("Authorization", auth(other)).param("employeeId", traineeId)
                        .param("from", day.toString()).param("to", day.toString()))
                .andExpect(status().isForbidden());
        // the team: the lead and the trainee, not the unrelated colleague
        byte[] team = mockMvc.perform(get("/api/worklog/export").header("Authorization", auth(lead)).param("all", "true")
                        .param("from", day.toString()).param("to", day.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(XlsxRead.rows(team, "Summary").stream().map(r -> r.size() > 1 ? r.get(1) : ""))
                .contains("Lakshmi Lead", "Tom Trainee").doesNotContain("Olivia Other");
    }

    @Test
    void impossibleHoursAreRefused() throws Exception {
        log(trainee, TODAY.plusDays(1), "MSC-1", "1", "Tomorrow").andExpect(status().isBadRequest());
        log(trainee, TODAY.minusDays(10), "MSC-1", "0.3", "Odd").andExpect(status().isBadRequest());
        log(trainee, TODAY.minusDays(10), "MSC-1", "20", "Long day").andExpect(status().isCreated());
        log(trainee, TODAY.minusDays(10), "MSC-2", "5", "Too long").andExpect(status().isBadRequest());
        log(trainee, TODAY.minusDays(10), "bad id!", "1", "x").andExpect(status().isBadRequest());
    }

    @Test
    void managersSeeTheirTeamsLogsButOnlyTheOwnerChangesThem() throws Exception {
        LocalDate day = TODAY.minusDays(5);
        String id = idOf(log(trainee, day, "MSC-201", "4", "Measurements"));

        month(lead, traineeId, day, day).andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.entries[0].taskId").value("MSC-201"));
        month(other, traineeId, day, day).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/worklog/entries/" + id).header("Authorization", auth(lead))
                        .contentType(MediaType.APPLICATION_JSON).content(entry(day, "MSC-201", "5", "Changed")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/worklog/team").header("Authorization", auth(lead)))
                .andExpect(jsonPath("$[*].name", hasItem("Tom Trainee")))
                .andExpect(jsonPath("$[*].name", not(hasItem("Olivia Other"))));

        mockMvc.perform(delete("/api/worklog/entries/" + id).header("Authorization", auth(trainee)))
                .andExpect(status().isNoContent());
    }

    @Test
    void levelRulesAndPersonalTargets() throws Exception {
        // each brand has its default, and its hours for a designation if needed
        String ruleJson = "{\"brandId\":%s,\"designationId\":%s,\"dailyMinHours\":%s,\"weeklyMinHours\":%s,\"halfDayBelow\":4,\"fullDayBelow\":1}";
        List<Integer> traineeIds = JsonPath.read(body(mockMvc.perform(get("/api/designations").header("Authorization", auth(owner)))), "$[?(@.name == 'Trainee')].id");
        int traineeDesignation = traineeIds.get(0);
        rule(lead, ruleJson.formatted(dw, null, 9, 54)).andExpect(status().isForbidden());
        rule(owner, ruleJson.formatted(dw, null, 3, 54)).andExpect(status().isBadRequest());
        // starts from the rule made for every brand on first start
        month(trainee, null, TODAY, TODAY).andExpect(jsonPath("$.target.source").value("DW default"));

        rule(owner, ruleJson.formatted(dw, null, 7.5, 45)).andExpect(status().isOk());
        month(trainee, null, TODAY, TODAY)
                .andExpect(jsonPath("$.target.dailyMinHours").value(7.5))
                .andExpect(jsonPath("$.target.source").value("DW default"));
        // without a brand default of its own, a brand uses the group's
        mockMvc.perform(delete("/api/worklog/rules").param("brandId", String.valueOf(dw)).header("Authorization", auth(owner))).andExpect(status().isOk());
        month(trainee, null, TODAY, TODAY).andExpect(jsonPath("$.target.source").value("Group default"));
        // the other tests expect the brand's first default
        rule(owner, "{\"brandId\":%d,\"dailyMinHours\":9,\"weeklyMinHours\":54,\"halfDayBelow\":4.5,\"fullDayBelow\":1}".formatted(dw)).andExpect(status().isOk());
        rule(owner, ruleJson.formatted(dw, traineeDesignation, 8, 48)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.brandId == %d && @.designationId == %d)].dailyMinHours".formatted(dw, traineeDesignation)).value(org.hamcrest.Matchers.hasItem(8)))
                .andExpect(jsonPath("$[?(@.brandId == %d && @.designationId == %d)].inherited.source".formatted(dw, traineeDesignation)).value(org.hamcrest.Matchers.hasItem("DW default")));
        month(trainee, null, TODAY, TODAY)
                .andExpect(jsonPath("$.target.dailyMinHours").value(8))
                .andExpect(jsonPath("$.target.source").value("Trainee rule"));
        // taken off: the brand's default again; the group default always stays
        mockMvc.perform(delete("/api/worklog/rules").param("brandId", String.valueOf(dw)).param("designationId", String.valueOf(traineeDesignation))
                .header("Authorization", auth(owner))).andExpect(status().isOk());
        month(trainee, null, TODAY, TODAY).andExpect(jsonPath("$.target.source").value("DW default"));
        mockMvc.perform(delete("/api/worklog/rules").header("Authorization", auth(owner))).andExpect(status().isBadRequest());
        rule(owner, ruleJson.formatted(dw, traineeDesignation, 8, 48)).andExpect(status().isOk());

        String target = "{\"dailyMinHours\":7,\"weeklyMinHours\":null}";
        mockMvc.perform(put("/api/worklog/targets/" + traineeId).header("Authorization", auth(trainee))
                        .contentType(MediaType.APPLICATION_JSON).content(target))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/worklog/targets/" + traineeId).header("Authorization", auth(other))
                        .contentType(MediaType.APPLICATION_JSON).content(target))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/worklog/targets/" + traineeId).header("Authorization", auth(lead))
                        .contentType(MediaType.APPLICATION_JSON).content(target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyMinHours").value(7))
                .andExpect(jsonPath("$.weeklyMinHours").value(48))
                .andExpect(jsonPath("$.source").value("Personal target"));
    }

    @Test
    void aBrandsTaskGoesToThatBrandsPeople() throws Exception {
        long wk = ((Number) JsonPath.read(body(mockMvc.perform(post("/api/brands").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"BR\",\"name\":\"Other Brand\"}"))), "$.id")).longValue();
        String json = "{\"code\":\"BRT-1\",\"title\":\"Other brand work\",\"status\":\"OPEN\",\"brandId\":%d,\"assigneeIds\":[%s]}";
        mockMvc.perform(post("/api/tasks").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content(json.formatted(wk, traineeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Tom Trainee is not in Other Brand; choose people of the task's brand"));
        // the same brand: fine; moved to another brand with them still on it: refused
        String id = idOf(mockMvc.perform(post("/api/tasks").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content(json.formatted(dw, traineeId)))
                .andExpect(status().isCreated()));
        mockMvc.perform(put("/api/tasks/" + id).header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content(json.formatted(wk, traineeId)))
                .andExpect(status().isBadRequest());
        // a task for the whole group can go to anyone
        mockMvc.perform(post("/api/tasks").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BRT-2\",\"title\":\"Group work\",\"status\":\"OPEN\",\"assigneeIds\":[%s]}".formatted(traineeId)))
                .andExpect(status().isCreated());
    }

    @Test
    void onlyAssignedOpenTasksCanBeLogged() throws Exception {
        // only owners and admins create tasks; an empty code gets the next TSK number
        task(lead, "", "Not allowed", "OPEN", traineeId).andExpect(status().isForbidden());
        String created = body(task(owner, "", "Kumar residence windows", "OPEN", traineeId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("TSK-")))
                .andExpect(jsonPath("$.assignees[0].name").value("Tom Trainee")));
        String code = JsonPath.read(created, "$.code");
        String id = String.valueOf((Object) JsonPath.read(created, "$.id"));

        mockMvc.perform(get("/api/tasks/mine").header("Authorization", auth(trainee)))
                .andExpect(jsonPath("$[*].code", hasItem(code)));
        mockMvc.perform(get("/api/tasks/mine").header("Authorization", auth(other)))
                .andExpect(jsonPath("$[*].code", not(hasItem(code))));

        LocalDate day = TODAY.minusDays(7);
        log(trainee, day, code, "2", "Measured openings").andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskTitle").value("Kumar residence windows"));
        log(other, day, code, "2", "Not mine").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Task " + code + " is not assigned to you"));
        log(trainee, day, "NO-SUCH-TASK", "1", "Unknown").andExpect(status().isBadRequest());
        approveAll();

        mockMvc.perform(put("/api/tasks/" + id).header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(taskJson(code, "Kumar residence windows", "CLOSED", traineeId)))
                .andExpect(status().isOk());
        log(trainee, day, code, "1", "After closing").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Task " + code + " is closed"));
        mockMvc.perform(get("/api/tasks").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.code == '" + code + "')].loggedHours", hasItem(2.0)));
    }

    @Test
    void leaveShowsInTheCalendarRange() throws Exception {
        List<String> codes = JsonPath.read(body(mockMvc.perform(get("/api/leave/types").header("Authorization", auth(owner)))), "$[*].code");
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/leave/types").header("Authorization", auth(owner)))), "$[*].id");
        int lwp = ids.get(codes.indexOf("LWP"));
        LocalDate day = TODAY.plusDays(TODAY.getDayOfWeek().getValue() == 6 ? 9 : 8); // a weekday ahead
        mockMvc.perform(post("/api/leave/me/requests").header("Authorization", auth(trainee))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveTypeId\":%d,\"fromDate\":\"%s\",\"toDate\":\"%s\",\"halfDay\":false}".formatted(lwp, day, day)))
                .andExpect(status().isCreated());
        month(trainee, null, day.minusDays(2), day.plusDays(2))
                .andExpect(jsonPath("$.leave[0].typeCode").value("LWP"))
                .andExpect(jsonPath("$.leave[0].status").value("PENDING"));
    }

    @Test
    void aTasksPageListsEveryoneElsesWorkLogAgainstIt() throws Exception {
        String id = String.valueOf((Object) JsonPath.read(body(task(owner, "MSC-401", "Site survey", "OPEN", traineeId)
                .andExpect(status().isCreated())), "$.id"));
        log(trainee, TODAY.minusDays(21), "MSC-401", "2", "Measured the ground floor").andExpect(status().isCreated());
        log(trainee, TODAY.minusDays(20), "MSC-401", "3.5", "Measured the first floor").andExpect(status().isCreated());
        approveAll();

        mockMvc.perform(get("/api/tasks/" + id + "/logs").header("Authorization", auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.code").value("MSC-401"))
                .andExpect(jsonPath("$.task.loggedHours").value(5.5))
                .andExpect(jsonPath("$.entries.length()").value(2))
                // newest day first, with who logged it
                .andExpect(jsonPath("$.entries[0].workDate").value(TODAY.minusDays(20).toString()))
                .andExpect(jsonPath("$.entries[0].hours").value(3.5))
                .andExpect(jsonPath("$.entries[0].description").value("Measured the first floor"))
                .andExpect(jsonPath("$.entries[0].employeeName").value("Tom Trainee"))
                .andExpect(jsonPath("$.entries[0].empNumber").exists());
        // only for people with Tasks
        mockMvc.perform(get("/api/tasks/" + id + "/logs").header("Authorization", auth(trainee))).andExpect(status().isForbidden());
    }

    @Test
    void aTaskCanBeForACustomer() throws Exception {
        String customer = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/trade/customers")
                .header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"Task Customer\",\"phone\":\"9847066601\"}".formatted(dw)))
                .andExpect(status().isCreated())), "$.id"));
        String code = JsonPath.read(body(mockMvc.perform(get("/api/trade/customers/" + customer).header("Authorization", auth(owner)))), "$.code");
        // the picker lists it, without the Customers menu being needed
        mockMvc.perform(get("/api/tasks/customers").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.code == '%s')].name".formatted(code)).value(hasItem("Task Customer")));

        String json = "{\"code\":\"CUST-1\",\"title\":\"Measure windows\",\"status\":\"OPEN\",\"brandId\":%d,\"customerId\":%s,\"assigneeIds\":[]}"
                .formatted(dw, customer);
        mockMvc.perform(post("/api/tasks").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(Integer.parseInt(customer)))
                .andExpect(jsonPath("$.customerCode").value(code))
                .andExpect(jsonPath("$.customerName").value("Task Customer"));
        mockMvc.perform(get("/api/tasks").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.code == 'CUST-1')].customerCode").value(hasItem(code)));

        // a customer of another brand does not go on this brand's task
        String otherBrand = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/brands").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"WK\",\"name\":\"Worklog Brand\"}"))
                .andExpect(status().isCreated())), "$.id"));
        mockMvc.perform(post("/api/tasks").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CUST-2\",\"title\":\"x\",\"status\":\"OPEN\",\"brandId\":%s,\"customerId\":%s,\"assigneeIds\":[]}"
                                .formatted(otherBrand, customer)))
                .andExpect(status().isBadRequest());
        // and a customer with a task stays
        mockMvc.perform(delete("/api/trade/customers/" + customer).header("Authorization", auth(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("work log tasks")));
    }

    // ---- helpers ----

    private ResultActions task(String token, String code, String title, String status, String assigneeId) throws Exception {
        return mockMvc.perform(post("/api/tasks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(taskJson(code, title, status, assigneeId)));
    }

    private ResultActions rule(String token, String json) throws Exception {
        return mockMvc.perform(put("/api/worklog/rules").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String taskJson(String code, String title, String status, String assigneeId) {
        return "{\"code\":\"%s\",\"title\":\"%s\",\"status\":\"%s\",\"brandId\":%d,\"assigneeIds\":[%s]}"
                .formatted(code, title, status, dw, assigneeId);
    }

    private ResultActions log(String token, LocalDate day, String task, String hours, String text) throws Exception {
        return mockMvc.perform(post("/api/worklog/entries").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(entry(day, task, hours, text)));
    }

    private static String entry(LocalDate day, String task, String hours, String text) {
        return "{\"workDate\":\"%s\",\"taskId\":\"%s\",\"hours\":%s,\"description\":\"%s\"}".formatted(day, task, hours, text);
    }

    private ResultActions month(String token, String employeeId, LocalDate from, LocalDate to) throws Exception {
        return mockMvc.perform(get("/api/worklog").header("Authorization", auth(token))
                .param("from", from.toString()).param("to", to.toString())
                .param("employeeId", employeeId == null ? "" : employeeId));
    }

    /** The owner approves everything waiting, as an approver would. */
    private void approveAll() throws Exception {
        String queue = mockMvc.perform(get("/api/work-approvals").header("Authorization", auth(owner))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> attendance = JsonPath.read(queue, "$.attendance[?(@.decidable == true)].id");
        List<Integer> logs = JsonPath.read(queue, "$.workLogs[*].id");
        mockMvc.perform(post("/api/work-approvals/decide").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendanceIds\":%s,\"workLogIds\":%s,\"approve\":true}".formatted(attendance, logs)))
                .andExpect(status().isOk());
    }

    private String create(String name, String userId, String role, String designation, String reportsTo) throws Exception {
        List<Integer> d = JsonPath.read(levels, "$[?(@.name == '" + designation + "')].id");
        String json = """
                {"name":"%s","userId":"%s","password":"ab@123","role":"%s","brandId":%d,"designationId":%d,
                 "reportsToId":%s,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(name, userId, role, dw, d.get(0), reportsTo);
        return idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated()));
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

    private static String idOf(ResultActions result) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), "$.id"));
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
