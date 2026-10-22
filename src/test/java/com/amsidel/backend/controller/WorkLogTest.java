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
        // a Team Member (level 3) has no level rule of its own: the default applies
        month(other, null, day, day)
                .andExpect(jsonPath("$.target.dailyMinHours").value(9))
                .andExpect(jsonPath("$.target.source").value("Default rule"))
                .andExpect(jsonPath("$.target.weeklyMinHours").value(54))
                .andExpect(jsonPath("$.target.halfDayBelow").value(4.5));
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
        String rules = """
                [{"levelNumber":0,"dailyMinHours":9,"weeklyMinHours":54,"halfDayBelow":4.5,"fullDayBelow":1},
                 {"levelNumber":1,"dailyMinHours":8,"weeklyMinHours":48,"halfDayBelow":4,"fullDayBelow":1}]
                """;
        mockMvc.perform(put("/api/worklog/rules").header("Authorization", auth(lead))
                        .contentType(MediaType.APPLICATION_JSON).content(rules))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/worklog/rules").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"levelNumber\":1,\"dailyMinHours\":8,"
                                + "\"weeklyMinHours\":48,\"halfDayBelow\":4,\"fullDayBelow\":1}]"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/worklog/rules").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(rules))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        month(trainee, null, TODAY, TODAY)
                .andExpect(jsonPath("$.target.dailyMinHours").value(8))
                .andExpect(jsonPath("$.target.source").value("Level 1 rule"));

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

    // ---- helpers ----

    private ResultActions task(String token, String code, String title, String status, String assigneeId) throws Exception {
        return mockMvc.perform(post("/api/tasks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(taskJson(code, title, status, assigneeId)));
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
