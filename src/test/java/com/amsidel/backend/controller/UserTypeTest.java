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

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * User types added and removed by owners and admins: each stands on an access level and has its own menus; the
 * built-in ones stay; a type or a designation is deleted only while no employee has it, and its menu access goes too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=usertypes")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserTypeTest {

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private long dw;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = brandIds.getFirst();
    }

    @Test
    void userTypesAddedGivenTheirMenusAndRemovedWhenNobodyHasThem() throws Exception {
        // the four built in
        mockMvc.perform(get("/api/user-types").header("Authorization", auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.builtIn == true)].code", containsInAnyOrder("OWNER", "ADMIN", "MANAGER", "EMPLOYEE")));
        int employeeType = typeId("EMPLOYEE");
        int ownerType = typeId("OWNER");

        // a new one on the employee level, with its own menus (Income & expenses only)
        String acc = body(json(post("/api/user-types"), owner, "{\"name\":\"Accountant\",\"baseRole\":\"EMPLOYEE\",\"description\":\"Books and bank\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("ACCOUNTANT")).andExpect(jsonPath("$.employees").value(0)));
        int accId = JsonPath.read(acc, "$.id");
        json(post("/api/user-types"), owner, "{\"name\":\"accountant\",\"baseRole\":\"EMPLOYEE\"}").andExpect(status().isConflict());
        json(post("/api/user-types"), owner, "{\"name\":\"Co-owner\",\"baseRole\":\"OWNER\"}").andExpect(status().isBadRequest());
        json(put("/api/menu-access/ROLE/ACCOUNTANT"), owner, "{\"checkpoints\":[\"11601\"]}").andExpect(status().isOk());

        // an employee given it: the employee level, the type's name, and only its menus
        String id = idOf(json(post("/api/employees"), owner, """
                {"name":"Anu Accounts","userId":"ut.anu","password":"ab@123","role":"EMPLOYEE","userTypeId":%d,"brandId":%d,
                 "phone":"+91 98470 00000","joinedDate":"2026-01-05"}
                """.formatted(accId, dw)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("EMPLOYEE")).andExpect(jsonPath("$.userTypeName").value("Accountant")));
        String anu = token(changePin(token(login("ut.anu", "ab@123")), "ab@123", "cd@456"));
        mockMvc.perform(get("/api/auth/access").header("Authorization", auth(anu)))
                .andExpect(jsonPath("$.codes", hasItem("PROD_LEDGER")))
                .andExpect(jsonPath("$.codes", not(hasItem("ATT_CHECKIN"))));
        // not for staff to change
        json(post("/api/user-types"), anu, "{\"name\":\"Boss\",\"baseRole\":\"ADMIN\"}").andExpect(status().isForbidden());

        // in use: not deleted; the built-in ones never
        mockMvc.perform(delete("/api/user-types/" + accId).header("Authorization", auth(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("1 employee has")));
        mockMvc.perform(delete("/api/user-types/" + employeeType).header("Authorization", auth(owner))).andExpect(status().isBadRequest());
        json(put("/api/user-types/" + ownerType), owner, "{\"name\":\"Owner\",\"baseRole\":\"ADMIN\"}").andExpect(status().isBadRequest());

        // in use: not changed either
        json(put("/api/user-types/" + accId), owner, "{\"name\":\"Senior accountant\",\"baseRole\":\"MANAGER\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("cannot be changed while in use")));
        mockMvc.perform(get("/api/employees/" + id).header("Authorization", auth(owner))).andExpect(jsonPath("$.role").value("EMPLOYEE"));

        // given back the built-in Employee type: the custom one can go, with its menu access
        json(put("/api/employees/" + id), owner, """
                {"name":"Anu Accounts","userId":"ut.anu","role":"EMPLOYEE","userTypeId":%d,"brandId":%d,"phone":"+91 98470 00000","joinedDate":"2026-01-05"}
                """.formatted(employeeType, dw)).andExpect(status().isOk()).andExpect(jsonPath("$.userTypeName").value("Employee"));
        // nobody has it now: it may change, then go
        json(put("/api/user-types/" + accId), owner, "{\"name\":\"Senior accountant\",\"baseRole\":\"MANAGER\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Senior accountant")).andExpect(jsonPath("$.baseRole").value("MANAGER"));
        mockMvc.perform(delete("/api/user-types/" + accId).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/menu-access").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.subjectKey == 'ACCOUNTANT')]").isEmpty());
    }

    @Test
    void aRoleCanBeForOneBrandOnly() throws Exception {
        int other = JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"UTB\",\"name\":\"Roles Brand\"}")), "$.id");
        // a role for DW, and one of the same name for the other brand
        String dwRole = body(json(post("/api/user-types"), owner, "{\"name\":\"Site supervisor\",\"baseRole\":\"MANAGER\",\"brandId\":%d}".formatted(dw))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.brandId").value((int) dw)).andExpect(jsonPath("$.brandCode").isNotEmpty()));
        int dwRoleId = JsonPath.read(dwRole, "$.id");
        json(post("/api/user-types"), owner, "{\"name\":\"Site supervisor\",\"baseRole\":\"MANAGER\",\"brandId\":%d}".formatted(other))
                .andExpect(status().isCreated());
        json(post("/api/user-types"), owner, "{\"name\":\"site SUPERVISOR\",\"baseRole\":\"EMPLOYEE\",\"brandId\":%d}".formatted(dw))
                .andExpect(status().isConflict());
        // only that brand's people may have it
        json(post("/api/employees"), owner, """
                {"name":"Off Brand","userId":"ut.off","password":"ab@123","role":"MANAGER","userTypeId":%d,"brandId":%d,"phone":"+91 98470 00001","joinedDate":"2026-01-05"}
                """.formatted(dwRoleId, other)).andExpect(status().isBadRequest());
        json(post("/api/employees"), owner, """
                {"name":"On Brand","userId":"ut.on","password":"ab@123","role":"MANAGER","userTypeId":%d,"brandId":%d,"phone":"+91 98470 00002","joinedDate":"2026-01-05"}
                """.formatted(dwRoleId, dw)).andExpect(status().isCreated()).andExpect(jsonPath("$.userTypeName").value("Site supervisor"));
        mockMvc.perform(get("/api/user-types").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.id == " + dwRoleId + ")].employees", hasItem(1)));
        // a brand's admin: their own brand's roles only, never the ones for every brand
        String admin = idOf(json(post("/api/employees"), owner, """
                {"name":"Brand Admin","userId":"ut.adm","password":"ab@123","role":"ADMIN","brandId":%d,"phone":"+91 98470 00003","joinedDate":"2026-01-05"}
                """.formatted(other)).andExpect(status().isCreated()));
        json(put("/api/menu-access/EMPLOYEE/" + admin), owner, "{\"checkpoints\":[\"9023\"]}").andExpect(status().isOk());
        String adm = token(changePin(token(login("ut.adm", "ab@123")), "ab@123", "cd@456"));
        json(post("/api/user-types"), adm, "{\"name\":\"Driver\",\"baseRole\":\"EMPLOYEE\",\"brandId\":%d}".formatted(other)).andExpect(status().isCreated());
        json(post("/api/user-types"), adm, "{\"name\":\"Driver\",\"baseRole\":\"EMPLOYEE\"}").andExpect(status().isForbidden());
        json(post("/api/user-types"), adm, "{\"name\":\"Driver\",\"baseRole\":\"EMPLOYEE\",\"brandId\":%d}".formatted(dw)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/user-types/" + dwRoleId).header("Authorization", auth(adm))).andExpect(status().isForbidden());
    }

    @Test
    void aDesignationIsDeletedOnlyWhileNobodyHasIt() throws Exception {
        String des = idOf(json(post("/api/designations"), owner, "{\"name\":\"Site supervisor\",\"levelNumber\":4,\"levelCode\":\"4.9\"}")
                .andExpect(status().isCreated()));
        json(put("/api/menu-access/DESIGNATION/" + des), owner, "{\"checkpoints\":[\"1001\"]}").andExpect(status().isOk());
        String id = idOf(json(post("/api/employees"), owner, """
                {"name":"Ravi Site","userId":"ut.ravi","password":"ab@123","role":"EMPLOYEE","brandId":%d,"designationId":%s,
                 "phone":"+91 98470 00000","joinedDate":"2026-01-05"}
                """.formatted(dw, des)).andExpect(status().isCreated()));
        mockMvc.perform(delete("/api/designations/" + des).header("Authorization", auth(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("1 employee has the designation \"Site supervisor\"")));
        json(put("/api/employees/" + id), owner, """
                {"name":"Ravi Site","userId":"ut.ravi","role":"EMPLOYEE","brandId":%d,"phone":"+91 98470 00000","joinedDate":"2026-01-05"}
                """.formatted(dw)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/designations/" + des).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/designations").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.name == 'Site supervisor')]").isEmpty());
        mockMvc.perform(get("/api/menu-access").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.subjectType == 'DESIGNATION' && @.subjectKey == '%s')]".formatted(des)).isEmpty());
    }

    // ---- helpers ----

    private int typeId(String code) throws Exception {
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/user-types").header("Authorization", auth(owner)))),
                "$[?(@.code == '" + code + "')].id");
        return ids.getFirst();
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
