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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard's Team view. Boss (DW) has Lead (DW), who has Worker (DW), plus Far (another brand) reporting to Lead;
 * Peer (DW) reports to nobody. So Boss's team is Lead and Worker: under them in the line, in the same brand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=teamdashboard")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamDashboardTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MenuAccessService menuAccess;

    private String owner;
    private String boss;
    private String bossId;
    private String lead;
    private String leadId;
    private String worker;
    private String workerId;
    private String far;
    private String farId;
    private String peer;
    private String peerId;
    private long dw;
    private long ob;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.get(0);
        ob = ((Number) JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"TB\",\"name\":\"Team Brand\"}")), "$.id")).longValue();
        bossId = person("Boss B", "team.boss", "MANAGER", dw, null);
        boss = signIn("team.boss");
        leadId = person("Lead L", "team.lead", "MANAGER", dw, bossId);
        lead = signIn("team.lead");
        workerId = person("Worker W", "team.worker", "EMPLOYEE", dw, leadId);
        worker = signIn("team.worker");
        farId = person("Far F", "team.far", "EMPLOYEE", ob, leadId);
        far = signIn("team.far");
        peerId = person("Peer P", "team.peer", "EMPLOYEE", dw, null);
        peer = signIn("team.peer");

        // two people's day: Worker logs 3 hours and checks in, Lead logs 2; a task for Worker, one closed for Lead
        task("TEAM-1", "Windows", dw, workerId);
        String closed = task("TEAM-2", "Doors", dw, leadId);
        json(put("/api/tasks/" + closed), owner, "{\"code\":\"TEAM-2\",\"title\":\"Doors\",\"brandId\":%d,\"status\":\"CLOSED\",\"assigneeIds\":[%s]}".formatted(dw, leadId))
                .andExpect(status().isOk());
        String farTask = task("TEAM-3", "Far away", ob, farId);
        assertThat(farTask).isNotEmpty();
        task("TEAM-4", "Lead's open", dw, leadId);
        json(post("/api/worklog/entries"), worker, "{\"workDate\":\"%s\",\"taskId\":\"TEAM-1\",\"hours\":3,\"description\":\"Fitting\"}".formatted(TODAY)).andExpect(status().isCreated());
        json(post("/api/worklog/entries"), lead, "{\"workDate\":\"%s\",\"taskId\":\"TEAM-4\",\"hours\":2,\"description\":\"Checking\"}".formatted(TODAY)).andExpect(status().isCreated());
        json(post("/api/worklog/entries"), far, "{\"workDate\":\"%s\",\"taskId\":\"TEAM-3\",\"hours\":5,\"description\":\"Elsewhere\"}".formatted(TODAY)).andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/attendance/check-in").param("workMode", "WFH").param("platform", "web").header("Authorization", auth(worker)))
                .andExpect(status().isCreated());
        // the managers can see their team's attendance and pay
        for (String id : List.of(bossId, leadId)) {
            menuAccess.save(AccessSubject.EMPLOYEE, id, List.of("1001", "1002", "2001", "2002", "3001", "7004", "8000"));
        }
    }

    @Test
    void myTeamIsEveryoneBelowMeInMyBrand() throws Exception {
        part("summary", boss).andExpect(jsonPath("$.people").value(2)).andExpect(jsonPath("$.canSplit").value(false));
        part("hours", boss).andExpect(jsonPath("$.today").value(5.0)).andExpect(jsonPath("$.week").value(5.0));
        part("tasks", boss).andExpect(jsonPath("$.completed").value(1)).andExpect(jsonPath("$.pending").value(2));
        part("attendance", boss).andExpect(jsonPath("$.week").exists());
        part("leave", boss).andExpect(jsonPath("$.taken").value(0.0));
        part("profile", boss).andExpect(jsonPath("$.people").value(2));
        String json = body(part("people", boss).andExpect(jsonPath("$.byBrand", hasSize(0))));
        List<String> names = JsonPath.read(json, "$.members[*].name");
        // two levels down are in; another brand, a peer and I are not
        assertThat(names).containsExactly("Lead L", "Worker W");
        assertThat(((Number) JsonPath.read(json, "$.members[1].tasksPending")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(json, "$.members[1].hoursToday")).doubleValue()).isEqualTo(3.0);
        assertThat((String) JsonPath.read(json, "$.members[1].status")).isEqualTo("CHECKED_IN");
        // Lead's team: only the one below them in their brand
        part("summary", lead).andExpect(jsonPath("$.people").value(1));
        part("hours", lead).andExpect(jsonPath("$.today").value(3.0));
    }

    @Test
    void someoneWithNobodyBelowThemHasNoTeam() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary").header("Authorization", auth(peer))).andExpect(jsonPath("$.hasTeam").value(false));
        mockMvc.perform(get("/api/dashboard/summary").header("Authorization", auth(boss))).andExpect(jsonPath("$.hasTeam").value(true));
        part("summary", peer).andExpect(jsonPath("$.people").value(0));
        part("people", peer).andExpect(jsonPath("$.members", hasSize(0)));
        part("hours", peer).andExpect(jsonPath("$.today").value(0));
    }

    @Test
    void theOwnerGetsEveryBrandSplitAndCanTakeOne() throws Exception {
        String summary = body(part("summary", owner).andExpect(jsonPath("$.canSplit").value(true)));
        String json = body(part("people", owner));
        Number farHours = ((List<Number>) JsonPath.read(json, "$.byBrand[?(@.code == 'TB')].hoursToday")).get(0);
        assertThat(farHours.doubleValue()).isEqualTo(5.0);
        Number dwHours = ((List<Number>) JsonPath.read(json, "$.byBrand[?(@.code == 'DW')].hoursToday")).get(0);
        assertThat(dwHours.doubleValue()).isEqualTo(5.0);
        part("hours", owner).andExpect(jsonPath("$.today").value(10.0));
        List<String> brandCodes = JsonPath.read(summary, "$.brands[*].code");
        assertThat(brandCodes).contains("DW", "TB");

        // one brand: only its people and figures, and the other brands stay to choose from
        String one = body(brandPart("summary", owner, ob).andExpect(jsonPath("$.brandId").value((int) ob)).andExpect(jsonPath("$.people").value(1)));
        brandPart("people", owner, ob).andExpect(jsonPath("$.members[0].name").value("Far F")).andExpect(jsonPath("$.byBrand", hasSize(0)));
        brandPart("hours", owner, ob).andExpect(jsonPath("$.today").value(5.0));
        assertThat((List<String>) JsonPath.read(one, "$.brands[*].code")).contains("DW", "TB");
    }

    @Test
    void onlyThoseWhoSpanBrandsCanChooseOne() throws Exception {
        for (String p : List.of("summary", "hours", "attendance", "leave", "tasks", "pay", "mail", "profile", "meetings-today", "people")) {
            mockMvc.perform(get("/api/dashboard/team/" + p).param("brandId", String.valueOf(ob)).header("Authorization", auth(boss)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/dashboard/team/summary").param("brandId", String.valueOf(dw)).header("Authorization", auth(boss))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/meetings").param("from", TODAY.toString()).param("to", TODAY.toString()).param("team", "true")
                .param("brandId", String.valueOf(ob)).header("Authorization", auth(boss))).andExpect(status().isForbidden());
    }

    @Test
    void aPartFollowsTheTeamPagesTick() throws Exception {
        String id = person("Mini M", "team.mini", "MANAGER", dw, null);
        person("Under U", "team.under", "EMPLOYEE", dw, id);
        String mini = signIn("team.mini");
        // My work log only: hours and tasks, and leave since anyone with people reporting to them gets Approvals; no team attendance or pay
        menuAccess.save(AccessSubject.EMPLOYEE, id, List.of("3001"));
        part("summary", mini).andExpect(jsonPath("$.people").value(1));
        part("hours", mini).andExpect(jsonPath("$.today").value(0));
        for (String refused : List.of("attendance", "pay")) {
            assertThat(body(part(refused, mini))).as(refused).isEmpty();
        }
        part("leave", mini).andExpect(jsonPath("$.taken").value(0));
        part("people", mini).andExpect(jsonPath("$.members[0].status").doesNotExist());
        part("mail", mini).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void payAddsUpForThoseWithTeamPay() throws Exception {
        part("pay", boss).andExpect(jsonPath("$.pay.salarySet").value(false)).andExpect(jsonPath("$.pay.received").value(0))
                .andExpect(jsonPath("$.receivedByMember['" + workerId + "']").value(0));
    }

    @Test
    void theTeamCalendarShowsTheirMeetings() throws Exception {
        LocalDate day = TODAY.plusDays(4);
        String withWorker = idOf(json(post("/api/meetings"), owner, meeting("With Worker", day, workerId)).andExpect(status().isCreated()));
        json(post("/api/meetings"), owner, meeting("With Peer", day, peerId)).andExpect(status().isCreated());
        json(post("/api/meetings"), owner, meeting("With Far", day, farId)).andExpect(status().isCreated());
        String json = body(mockMvc.perform(get("/api/meetings").param("from", day.toString()).param("to", day.toString()).param("team", "true")
                .header("Authorization", auth(boss))).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(json, "$.meetings[*].title")).containsExactly("With Worker");
        assertThat((Boolean) JsonPath.read(json, "$.meetings[0].canChange")).isFalse();
        // my own calendar stays mine
        mockMvc.perform(get("/api/meetings").param("from", day.toString()).param("to", day.toString()).header("Authorization", auth(boss)))
                .andExpect(jsonPath("$.meetings", hasSize(0)));
        // no team, nothing to see
        mockMvc.perform(get("/api/meetings").param("from", day.toString()).param("to", day.toString()).param("team", "true").header("Authorization", auth(peer)))
                .andExpect(jsonPath("$.meetings", hasSize(0)));
        // the owner, one brand: only that brand's people's meetings
        String ownerOne = body(mockMvc.perform(get("/api/meetings").param("from", day.toString()).param("to", day.toString()).param("team", "true")
                .param("brandId", String.valueOf(ob)).header("Authorization", auth(owner))));
        assertThat((List<String>) JsonPath.read(ownerOne, "$.meetings[*].title")).containsExactly("With Far");
        assertThat(withWorker).isNotEmpty();
    }

    private ResultActions part(String name, String token) throws Exception {
        return mockMvc.perform(get("/api/dashboard/team/" + name).header("Authorization", auth(token))).andExpect(status().isOk());
    }

    private ResultActions brandPart(String name, String token, long brandId) throws Exception {
        return mockMvc.perform(get("/api/dashboard/team/" + name).param("brandId", String.valueOf(brandId)).header("Authorization", auth(token))).andExpect(status().isOk());
    }

    private String meeting(String title, LocalDate date, String personId) {
        return "{\"title\":\"%s\",\"date\":\"%s\",\"startTime\":\"10:00\",\"peopleIds\":[%s]}".formatted(title, date, personId);
    }

    private String task(String code, String title, long brand, String assignee) throws Exception {
        return idOf(json(post("/api/tasks"), owner, "{\"code\":\"%s\",\"title\":\"%s\",\"brandId\":%d,\"assigneeIds\":[%s]}".formatted(code, title, brand, assignee))
                .andExpect(status().isCreated()));
    }

    private String person(String name, String userId, String role, long brandId, String reportsTo) throws Exception {
        return idOf(json(post("/api/employees"), owner, """
                {"name":"%s","userId":"%s","password":"ab@123","role":"%s","brandId":%d,"reportsToId":%s,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(name, userId, role, brandId, reportsTo)).andExpect(status().isCreated()));
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
