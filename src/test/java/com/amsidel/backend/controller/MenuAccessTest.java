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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Menu access: user types and designations get checkpoints; the API follows them, not just the menu. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=menus")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MenuAccessTest {

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private String admin;
    private long dw;
    private String levels;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.get(0);
        levels = body(mockMvc.perform(get("/api/designations").header("Authorization", auth(owner))));
        create("Asha Admin", "ma.admin", "ADMIN", null);
        admin = signIn("ma.admin");
    }

    @Test
    void defaultsKeepThePortalAsItWas() throws Exception {
        create("Eby Employee", "ma.eby", "EMPLOYEE", "Team Member");
        String eby = signIn("ma.eby");
        access(eby).andExpect(status().isOk())
                .andExpect(jsonPath("$.all").value(false))
                .andExpect(jsonPath("$.codes", containsInAnyOrder("ATT_CHECKIN", "LEAVE_MY", "WORKLOG_MY", "PERF_MY")))
                .andExpect(jsonPath("$.menus[0].code").value("ATTENDANCE"))
                .andExpect(jsonPath("$.menus[0].children[*].code", containsInAnyOrder("ATT_CHECKIN")));
        // the API follows the menus, not just the sidebar
        mockMvc.perform(get("/api/tasks").header("Authorization", auth(eby))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tasks/mine").header("Authorization", auth(eby))).andExpect(status().isOk());
        mockMvc.perform(get("/api/attendance/day").header("Authorization", auth(eby))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/mail/setup").header("Authorization", auth(eby))).andExpect(status().isForbidden());

        access(owner).andExpect(jsonPath("$.all").value(true)).andExpect(jsonPath("$.codes", hasItem("MENU_ACCESS")));
        access(admin).andExpect(jsonPath("$.all").value(true));
    }

    @Test
    void aDesignationGetsOnlyItsCheckpoints() throws Exception {
        String finance = designationId("Finance Manager");
        create("Fathima Finance", "ma.fathima", "EMPLOYEE", "Finance Manager");
        create("Dev Designer", "ma.dev", "EMPLOYEE", "Associate");
        String fathima = signIn("ma.fathima");
        String dev = signIn("ma.dev");

        // Tasks (3002) and the whole Attendance menu (1000 = Check in + Team attendance)
        save(owner, "DESIGNATION", finance, "[\"3002\",\"1000\"]").andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints", containsInAnyOrder("1000", "3002")));
        access(fathima)
                .andExpect(jsonPath("$.codes", hasItem("TASKS")))
                .andExpect(jsonPath("$.codes", hasItem("ATT_TEAM")))
                .andExpect(jsonPath("$.codes", not(hasItem("WORK_RULES"))));
        mockMvc.perform(get("/api/tasks").header("Authorization", auth(fathima))).andExpect(status().isOk());
        mockMvc.perform(get("/api/attendance/day").header("Authorization", auth(fathima))).andExpect(status().isOk());
        // another designation is not affected
        mockMvc.perform(get("/api/tasks").header("Authorization", auth(dev))).andExpect(status().isForbidden());
        access(dev).andExpect(jsonPath("$.codes", not(hasItem("TASKS"))));

        // taking it away applies at once
        save(owner, "DESIGNATION", finance, "[]").andExpect(status().isOk());
        mockMvc.perform(get("/api/tasks").header("Authorization", auth(fathima))).andExpect(status().isForbidden());
    }

    @Test
    void recordsAndAccessStayWithOwnersAndAdmins() throws Exception {
        String hr = designationId("HR Head / Manager");
        create("Hari HR", "ma.hari", "EMPLOYEE", "HR Head / Manager");
        String hari = signIn("ma.hari");
        save(admin, "DESIGNATION", hr, "[\"*\"]").andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints", containsInAnyOrder("*")));
        access(hari).andExpect(jsonPath("$.all").value(true));

        // every menu, so the employee list and the access screen open...
        mockMvc.perform(get("/api/employees").header("Authorization", auth(hari))).andExpect(status().isOk());
        mockMvc.perform(get("/api/menu-access").header("Authorization", auth(hari))).andExpect(status().isOk());
        // ...but changing records or access still needs the Owner or Admin user type
        mockMvc.perform(post("/api/employees").header("Authorization", auth(hari)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        save(hari, "ROLE", "EMPLOYEE", "[\"*\"]").andExpect(status().isForbidden());

        save(admin, "DESIGNATION", hr, "[]").andExpect(status().isOk());
    }

    @Test
    void aPersonsOwnAccessReplacesTheirUserTypeAndDesignation() throws Exception {
        String uma = create("Uma User", "ma.uma", "EMPLOYEE", "Team Member");
        String umaToken = signIn("ma.uma");
        access(umaToken).andExpect(jsonPath("$.codes", hasItem("ATT_CHECKIN")));

        // only Tasks for Uma: the employee defaults (check in, my leave, my work log) no longer count for her
        save(owner, "EMPLOYEE", uma, "[\"3002\"]").andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Uma User · ma.uma · DW0003".replace("DW0003", empNumber(uma))));
        access(umaToken)
                .andExpect(jsonPath("$.codes", containsInAnyOrder("TASKS")))
                .andExpect(jsonPath("$.menus[0].code").value("WORKLOG"))
                .andExpect(jsonPath("$.menus[0].children[*].code", containsInAnyOrder("TASKS")));
        mockMvc.perform(get("/api/tasks").header("Authorization", auth(umaToken))).andExpect(status().isOk());
        mockMvc.perform(get("/api/attendance/today").header("Authorization", auth(umaToken))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/menu-access").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.subjectType == 'EMPLOYEE' && @.subjectKey == '" + uma + "')].checkpoints[0]").value(hasItem("3002")));

        // removing her own setting brings back her user type's menus
        save(owner, "EMPLOYEE", uma, "[]").andExpect(status().isOk());
        access(umaToken).andExpect(jsonPath("$.codes", containsInAnyOrder("ATT_CHECKIN", "LEAVE_MY", "WORKLOG_MY", "PERF_MY", "MY_PAY")));
        // owners cannot be given less
        save(owner, "EMPLOYEE", JsonPath.read(body(mockMvc.perform(get("/api/auth/me").header("Authorization", auth(owner)))), "$.id").toString(),
                "[\"1001\"]").andExpect(status().isBadRequest());
    }

    @Test
    void nobodyGetsInWithoutAnyPermission() throws Exception {
        create("Nina None", "ma.nina", "EMPLOYEE", null);
        String nina = signIn("ma.nina");
        save(owner, "ROLE", "EMPLOYEE", "[]").andExpect(status().isOk());
        try {
            access(nina).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("No permission granted")));
            mockMvc.perform(get("/api/attendance/today").header("Authorization", auth(nina))).andExpect(status().isForbidden());
        } finally {
            save(owner, "ROLE", "EMPLOYEE", "[\"1001\",\"2001\",\"3001\",\"6001\"]").andExpect(status().isOk());
        }
        access(nina).andExpect(status().isOk());
    }

    @Autowired
    private com.amsidel.backend.config.MenuBootstrap menuBootstrap;

    @Autowired
    private com.amsidel.backend.repository.MenuItemRepository menuItems;

    @Autowired
    private com.amsidel.backend.repository.MenuAccessRepository menuAccessRows;

    @Test
    void aNewMenuReachesTheUserTypesOfAnExistingSetUp() throws Exception {
        // as if this database was set up before the Performance menus existed
        menuAccessRows.findAll().stream().filter(a -> a.getCheckpoint().startsWith("600")).forEach(menuAccessRows::delete);
        menuItems.findAll().stream().filter(m -> m.getCheckpoint() >= 6000 && m.getCheckpoint() < 7000).forEach(menuItems::delete);
        menuBootstrap.run(null);

        create("Neil New", "ma.neil", "EMPLOYEE", null);
        access(signIn("ma.neil")).andExpect(jsonPath("$.codes", hasItem("PERF_MY")))
                .andExpect(jsonPath("$.codes", not(hasItem("PERF_TEAM"))));
        mockMvc.perform(get("/api/menu-access").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.subjectKey == 'MANAGER')].checkpoints[*]").value(hasItems("6001", "6002")));
    }

    @Test
    void badChangesAreRefused() throws Exception {
        save(owner, "ROLE", "OWNER", "[]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Owners always have every menu"));
        save(owner, "ROLE", "EMPLOYEE", "[\"7777\"]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("There is no menu with checkpoint 7777"));
        save(owner, "DESIGNATION", "999999", "[\"1001\"]").andExpect(status().isBadRequest());
        save(owner, "ROLE", "NOBODY", "[\"1001\"]").andExpect(status().isBadRequest());
        // "*" with numbers keeps just "*"
        save(owner, "ROLE", "MANAGER", "[\"*\",\"1001\"]").andExpect(jsonPath("$.checkpoints", containsInAnyOrder("*")));
        save(owner, "ROLE", "MANAGER", "[\"1001\",\"1002\",\"2001\",\"2002\",\"3001\",\"6001\",\"6002\"]").andExpect(status().isOk());

        mockMvc.perform(get("/api/menu-access/menus").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.code == 'ATTENDANCE')].checkpoint").value(hasItem(1000)))
                .andExpect(jsonPath("$[?(@.code == 'ATTENDANCE')].children[*].checkpoint").value(containsInAnyOrder(1001, 1002)));
        mockMvc.perform(get("/api/menu-access").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.subjectKey == 'ADMIN')].checkpoints[0]").value(hasItem("*")));
    }

    // ---- helpers ----

    private ResultActions access(String token) throws Exception {
        return mockMvc.perform(get("/api/auth/access").header("Authorization", auth(token)));
    }

    private ResultActions save(String token, String type, String key, String checkpoints) throws Exception {
        return mockMvc.perform(put("/api/menu-access/" + type + "/" + key).header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"checkpoints\":" + checkpoints + "}"));
    }

    private String empNumber(String employeeId) throws Exception {
        return JsonPath.read(body(mockMvc.perform(get("/api/employees/" + employeeId).header("Authorization", auth(owner)))), "$.empNumber");
    }

    private String designationId(String name) {
        List<Integer> ids = JsonPath.read(levels, "$[?(@.name == '" + name + "')].id");
        return ids.get(0).toString();
    }

    private String create(String name, String userId, String role, String designation) throws Exception {
        String json = """
                {"name":"%s","userId":"%s","password":"ab@123","role":"%s","brandId":%s,"designationId":%s,
                 "phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(name, userId, role, "ADMIN".equals(role) ? "null" : String.valueOf(dw),
                designation == null ? "null" : designationId(designation));
        return idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated()));
    }

    private String signIn(String userId) throws Exception {
        return token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
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
