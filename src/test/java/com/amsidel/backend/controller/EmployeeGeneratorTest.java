/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.repository.EmployeeRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Administration > Employee generator. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=employeegenerator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmployeeGeneratorTest {

    private static final String API = "/api/employee-generator";
    private static final String TICK = "9021";
    private static final String PASSWORD = "Abc#123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EmployeeRepository employees;

    private String owner;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
    }

    /** {brand id, code}: a brand of its own, so each test counts from its own numbers. */
    private Object[] brand(String code) throws Exception {
        int id = JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"" + code + "\",\"name\":\"Brand " + code + "\"}").andExpect(status().isCreated())), "$.id");
        return new Object[]{id, code};
    }

    private int designation(String name, Integer brand) throws Exception {
        String res = body(json(post("/api/designations"), owner, """
                {"name":"%s","levelNumber":3,"levelCode":"3","active":true,"brandId":%s}
                """.formatted(name, brand)).andExpect(status().isCreated()));
        return JsonPath.read(res, "$.id");
    }

    private int anyDesignation() throws Exception {
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/designations").header("Authorization", auth(owner)))), "$[?(@.brandId == null && @.active == true)].id");
        return ids.getFirst();
    }

    private ResultActions generate(String token, int brand, int designation, int count, String password, Boolean temporary) throws Exception {
        return json(post(API), token, """
                {"brandId":%d,"designationId":%d,"count":%d,"password":"%s","temporaryPassword":%s}
                """.formatted(brand, designation, count, password, temporary));
    }

    private String person(int brand, String... checkpoints) throws Exception {
        String userId = "gen.person" + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(json(post("/api/employees"), owner, """
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, brand)).andExpect(status().isCreated())), "$.id"));
        if (checkpoints.length > 0) {
            json(put("/api/menu-access/EMPLOYEE/" + id), owner, "{\"checkpoints\":[\"" + String.join("\",\"", checkpoints) + "\"]}").andExpect(status().isOk());
        }
        return token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
    }

    @Test
    void theyGetTheNextNumbersAsTheirIdNameAndUserId() throws Exception {
        Object[] b = brand("GA");
        int d = anyDesignation();
        mockMvc.perform(get(API + "/next").param("brandId", b[0].toString()).header("Authorization", auth(owner))).andExpect(jsonPath("$.next").value("GA0001"));

        generate(owner, (int) b[0], d, 3, PASSWORD, false).andExpect(status().isCreated()).andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.first").value("GA0001")).andExpect(jsonPath("$.last").value("GA0003")).andExpect(jsonPath("$.temporaryPassword").value(false))
                .andExpect(jsonPath("$.brandCode").value("GA")).andExpect(jsonPath("$.password").doesNotExist());

        Employee e = employees.findByUserId("ga0002").orElseThrow();
        assertThat(e.getEmpNumber()).isEqualTo("GA0002");
        assertThat(e.getName()).isEqualTo("GA0002");
        assertThat(e.isActive()).isTrue();
        assertThat(e.getJoinedDate()).isNotNull();

        // and the next batch carries on from there
        mockMvc.perform(get(API + "/next").param("brandId", b[0].toString()).header("Authorization", auth(owner))).andExpect(jsonPath("$.next").value("GA0004"));
        generate(owner, (int) b[0], d, 2, PASSWORD, false).andExpect(jsonPath("$.first").value("GA0004")).andExpect(jsonPath("$.last").value("GA0005"));
        mockMvc.perform(get("/api/employees/" + e.getId()).header("Authorization", auth(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.designationId").value(d)).andExpect(jsonPath("$.brandCode").value("GA"));
    }

    @Test
    void itStartsAfterTheLastOneEvenWithGapsAndTakenIds() throws Exception {
        Object[] b = brand("GB");
        int d = anyDesignation();
        // someone added by hand with a high number
        json(post("/api/employees"), owner, """
                {"name":"Hand","userId":"hand.gb","empNumber":"GB0009","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(b[0])).andExpect(status().isCreated());
        // and someone using a user ID that the numbers will reach
        json(post("/api/employees"), owner, """
                {"name":"Taken","userId":"gb0011","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(b[0])).andExpect(status().isCreated());

        // the taken user ID's holder got GB0010, so GB0011 cannot be used as both
        generate(owner, (int) b[0], d, 2, PASSWORD, false).andExpect(status().isCreated())
                .andExpect(jsonPath("$.first").value("GB0012")).andExpect(jsonPath("$.last").value("GB0013"));

        // numbers past 9999 sort wrongly as text, but count rightly
        json(post("/api/employees"), owner, """
                {"name":"Big","userId":"big.gb","empNumber":"GB10000","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(b[0])).andExpect(status().isCreated());
        generate(owner, (int) b[0], d, 1, PASSWORD, false).andExpect(jsonPath("$.first").value("GB10001"));
    }

    @Test
    void aTemporaryPasswordMustBeChangedAndAPermanentOneNeedNot() throws Exception {
        Object[] b = brand("GC");
        int d = anyDesignation();
        generate(owner, (int) b[0], d, 1, PASSWORD, true).andExpect(status().isCreated()).andExpect(jsonPath("$.first").value("GC0001"));
        generate(owner, (int) b[0], d, 1, PASSWORD, false).andExpect(status().isCreated()).andExpect(jsonPath("$.first").value("GC0002"));

        String temp = body(login("gc0001", PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword").value(true)));
        mockMvc.perform(get("/api/tasks/mine").header("Authorization", auth(JsonPath.read(temp, "$.token")))).andExpect(status().isForbidden());

        // the user ID in capitals works too, and a permanent one can work at once
        String perm = body(login("GC0002", PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword").value(false)));
        mockMvc.perform(get("/api/tasks/mine").header("Authorization", auth(JsonPath.read(perm, "$.token")))).andExpect(status().isOk());
        assertThat(employees.findByUserId("gc0002").orElseThrow().isMustChangePassword()).isFalse();
    }

    @Test
    void whatIsAskedForIsChecked() throws Exception {
        Object[] b = brand("GD");
        Object[] other = brand("GE");
        int d = anyDesignation();
        int theirs = designation("Only GE", (int) other[0]);
        generate(owner, (int) b[0], d, 0, PASSWORD, false).andExpect(status().isBadRequest());
        generate(owner, (int) b[0], d, 501, PASSWORD, false).andExpect(status().isBadRequest());
        generate(owner, (int) b[0], d, 1, "weak", false).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("PIN must have")));
        // the question has to be answered
        generate(owner, (int) b[0], d, 1, PASSWORD, null).andExpect(status().isBadRequest());
        generate(owner, (int) b[0], theirs, 1, PASSWORD, false).andExpect(status().isBadRequest());
        generate(owner, 999_999, d, 1, PASSWORD, false).andExpect(status().isBadRequest());
        generate(owner, (int) b[0], 999_999, 1, PASSWORD, false).andExpect(status().isBadRequest());
        // nothing refused was made
        mockMvc.perform(get(API + "/next").param("brandId", b[0].toString()).header("Authorization", auth(owner))).andExpect(jsonPath("$.next").value("GD0001"));
    }

    private ResultActions generateUnder(String token, int brand, int designation, int count, Object reportsTo) throws Exception {
        return json(post(API), token, """
                {"brandId":%d,"designationId":%d,"count":%d,"password":"%s","temporaryPassword":false,"reportsToId":%s}
                """.formatted(brand, designation, count, PASSWORD, reportsTo));
    }

    @Test
    void theyCanAllReportToOnePerson() throws Exception {
        Object[] b = brand("GH");
        Object[] other = brand("GI");
        int d = anyDesignation();
        String boss = person((int) b[0]);
        String bossUser = "gen.person" + seq;
        Long bossId = employees.findByUserId(bossUser).orElseThrow().getId();
        String outsider = person((int) other[0]);
        Long outsiderId = employees.findByUserId("gen.person" + seq).orElseThrow().getId();
        assertThat(outsider).isNotBlank();

        // the brand's people and the owner are offered; another brand's are not
        mockMvc.perform(get(API + "/superiors").param("brandId", b[0].toString()).header("Authorization", auth(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(bossId.intValue()))).andExpect(jsonPath("$[*].empNumber", hasItem(startsWith("GRP"))))
                .andExpect(jsonPath("$[*].id", not(hasItem(outsiderId.intValue()))));

        // not leading anyone yet
        mockMvc.perform(get("/api/auth/access").header("Authorization", auth(boss))).andExpect(jsonPath("$.codes", not(hasItem("PERF_TEAM"))));

        generateUnder(owner, (int) b[0], d, 2, bossId).andExpect(status().isCreated()).andExpect(jsonPath("$.reportsToName").value(employees.findByUserId(bossUser).orElseThrow().getName()));
        String first = "gh000" + (employees.findByUserId(bossUser).orElseThrow().getEmpNumber().endsWith("1") ? "2" : "1");
        Employee made = employees.findWithBrandById(employees.findByUserId(first).orElseThrow().getId()).orElseThrow();
        Long reportsTo = mockMvcReportsTo(made.getId());
        assertThat(reportsTo).isEqualTo(bossId);

        // straight away they lead a team, and an entry of theirs waits for this person
        mockMvc.perform(get("/api/auth/access").header("Authorization", auth(boss))).andExpect(jsonPath("$.codes", hasItem("PERF_TEAM")));
        String theirs = JsonPath.read(body(login(first, PASSWORD)), "$.token");
        mockMvc.perform(multipart("/api/attendance/check-in").file(new org.springframework.mock.web.MockMultipartFile("photo", "me.jpg", "image/jpeg", jpeg())).param("workMode", "WFH").param("platform", "web").header("Authorization", auth(theirs)));
        mockMvc.perform(get("/api/work-approvals").header("Authorization", auth(boss))).andExpect(status().isOk());

        // someone of another brand, or who has left, or who does not exist, cannot be chosen; nothing is made
        String before = JsonPath.read(body(mockMvc.perform(get(API + "/next").param("brandId", b[0].toString()).header("Authorization", auth(owner)))), "$.next");
        generateUnder(owner, (int) b[0], d, 1, outsiderId).andExpect(status().isBadRequest());
        generateUnder(owner, (int) b[0], d, 1, 999_999).andExpect(status().isBadRequest());
        mockMvc.perform(get(API + "/next").param("brandId", b[0].toString()).header("Authorization", auth(owner))).andExpect(jsonPath("$.next").value(before));

        // it stays optional
        generateUnder(owner, (int) b[0], d, 1, "null").andExpect(status().isCreated()).andExpect(jsonPath("$.reportsToName").doesNotExist());
    }

    private Long mockMvcReportsTo(Long id) throws Exception {
        Number n = JsonPath.read(body(mockMvc.perform(get("/api/employees/" + id).header("Authorization", auth(owner)))), "$.reportsToId");
        return n.longValue();
    }

    private static byte[] jpeg() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    @Test
    void onlyThoseWithTheTickAndOnlyForTheirBrand() throws Exception {
        Object[] mine = brand("GF");
        Object[] other = brand("GG");
        int d = anyDesignation();
        String plain = person((int) mine[0]);
        generate(plain, (int) mine[0], d, 1, PASSWORD, false).andExpect(status().isForbidden());
        mockMvc.perform(get(API + "/next").param("brandId", mine[0].toString()).header("Authorization", auth(plain))).andExpect(status().isForbidden());

        String given = person((int) mine[0], TICK);
        generate(given, (int) other[0], d, 1, PASSWORD, false).andExpect(status().isForbidden());
        mockMvc.perform(get(API + "/next").param("brandId", other[0].toString()).header("Authorization", auth(given))).andExpect(status().isForbidden());
        generate(given, (int) mine[0], d, 1, PASSWORD, false).andExpect(status().isCreated());
        // they are plain employees, whoever makes them
        assertThat(employees.findByUserId("gf0003").orElseThrow().getRole().name()).isEqualTo("EMPLOYEE");
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
