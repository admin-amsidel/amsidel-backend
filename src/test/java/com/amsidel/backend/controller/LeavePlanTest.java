/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.LeavePlan;
import com.amsidel.backend.repository.LeavePlanRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Leave plans per brand, carry forward, who approves, the filters, and holidays per brand. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=leaveplan")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeavePlanTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LeavePlanRepository plans;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private com.amsidel.backend.repository.LeaveRequestRepository leaveRequests;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private String owner;
    private long dw;
    private long other;
    private final Map<String, Integer> typeIds = new HashMap<>();
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.get(0);
        other = ((Number) JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"LP\",\"name\":\"Plan Brand\"}")), "$.id")).longValue();
        String types = body(mockMvc.perform(get("/api/leave/types").header("Authorization", auth(owner))));
        List<String> codes = JsonPath.read(types, "$[*].code");
        List<Integer> typeList = JsonPath.read(types, "$[*].id");
        for (int i = 0; i < codes.size(); i++) {
            typeIds.put(codes.get(i), typeList.get(i));
        }
    }

    /** Each test starts with DW's plan running around today, ending far enough away, and no other plan. */
    @BeforeEach
    void freshPlan() {
        leaveRequests.deleteAll();
        transactions.executeWithoutResult(s -> {
            List<LeavePlan> dwPlans = plans.allFull().stream().filter(p -> p.getBrand() != null && p.getBrand().getId() == dw).toList();
            LeavePlan first = dwPlans.getFirst();
            first.setValidFrom(today.minusDays(120));
            first.setValidUntil(today.plusDays(120));
            first.setExpiryNotifiedAt(null);
            first.getLines().forEach(l -> {
                l.setCarryForward(false);
                l.setMaxCarryDays(null);
            });
            dwPlans.stream().skip(1).forEach(plans::delete);
        });
    }

    @Test
    void thePersonSeesOnlyThePlanInForceWithTodayAndTheirBalance() throws Exception {
        String[] emp = person("EMPLOYEE", null);
        mockMvc.perform(get("/api/leave/me/current").header("Authorization", auth(emp[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandCode").value("DW"))
                .andExpect(jsonPath("$.validFrom").value(today.minusDays(120).toString()))
                .andExpect(jsonPath("$.validUntil").value(today.plusDays(120).toString()))
                .andExpect(jsonPath("$.today").value(today.toString()))
                .andExpect(jsonPath("$.balances[?(@.code == 'CL')].entitled").value(hasItem(10.0)))
                .andExpect(jsonPath("$.balances[?(@.code == 'CL')].available").value(hasItem(10.0)));
    }

    @Test
    void theNextPlanCanBeAddedOnlyInTheLast10DaysAndOnlyOne() throws Exception {
        String next = planJson(dw, today.plusDays(121), today.plusDays(300), line("CL", 12, false, null));
        json(post("/api/leave/plans"), owner, next).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(startsWith("The next plan can be added from")));
        mockMvc.perform(get("/api/leave/plans/alerts").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.brandCode == 'DW')]").isEmpty());

        // the current plan now ends in 5 days: the alert comes, and the next plan can be added
        Map<String, Object> current = currentPlan(dw);
        json(put("/api/leave/plans/" + current.get("id")), owner, planJson(dw, today.minusDays(120), today.plusDays(5),
                line("CL", 10, false, null), line("SL", 10, false, null), line("LWP", null, false, null)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/leave/plans/alerts").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.brandCode == 'DW')].daysLeft").value(hasItem(5)));

        // not overlapping, and a date after it ends
        json(post("/api/leave/plans"), owner, planJson(dw, today.plusDays(5), today.plusDays(100), line("CL", 12, false, null)))
                .andExpect(status().isBadRequest());
        String added = body(json(post("/api/leave/plans"), owner, planJson(dw, today.plusDays(6), today.plusDays(300), line("CL", 12, false, null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("FUTURE")));
        json(post("/api/leave/plans"), owner, planJson(dw, today.plusDays(301), today.plusDays(400), line("CL", 12, false, null)))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/leave/plans/alerts").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.brandCode == 'DW')]").isEmpty());

        // the person does not see it, and cannot apply in it yet
        String[] emp = person("EMPLOYEE", null);
        mockMvc.perform(get("/api/leave/me/current").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.validUntil").value(today.plusDays(5).toString()))
                .andExpect(jsonPath("$.balances[?(@.code == 'CL')].entitled").value(hasItem(10.0)));
        apply(emp[1], "CL", workingDay(today.plusDays(20))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(startsWith("Leave can be applied for only inside the current leave plan")));
        // a type left out of the plan is not available to that brand
        apply(emp[1], "EL", workingDay(today.plusDays(1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Earned Leave is not in your brand's leave plan"));

        // a plan not yet started can be removed; the one in force cannot
        mockMvc.perform(delete("/api/leave/plans/" + current.get("id")).header("Authorization", auth(owner))).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/leave/plans/" + JsonPath.read(added, "$.id")).header("Authorization", auth(owner))).andExpect(status().isNoContent());
    }

    @Test
    void whatIsLeftCarriesForwardUpToTheLimit() throws Exception {
        String[] emp = person("EMPLOYEE", null);
        // used 2 of 10 CL in the plan before (no reporting person: approved at once)
        LocalDate day = workingDay(today.minusDays(10));
        apply(emp[1], "CL", day).andExpect(status().isCreated());
        apply(emp[1], "CL", workingDay(day.minusDays(1))).andExpect(status().isCreated());

        Map<String, Object> current = currentPlan(dw);
        json(put("/api/leave/plans/" + current.get("id")), owner, planJson(dw, today.minusDays(120), today.plusDays(5),
                line("CL", 10, true, 3), line("SL", 10, false, null), line("EL", 10, true, null))).andExpect(status().isOk());
        json(post("/api/leave/plans"), owner, planJson(dw, today.plusDays(6), today.plusDays(300),
                line("CL", 10, false, null), line("SL", 10, false, null), line("EL", 10, false, null))).andExpect(status().isCreated());

        // time moves on: the first plan has ended and the next is in force
        transactions.executeWithoutResult(s -> {
            List<LeavePlan> dwPlans = plans.allFull().stream().filter(p -> p.getBrand() != null && p.getBrand().getId() == dw).toList();
            dwPlans.get(0).setValidUntil(today.minusDays(1));
            dwPlans.get(1).setValidFrom(today);
        });
        String balances = body(mockMvc.perform(get("/api/leave/me/balances").header("Authorization", auth(emp[1]))).andExpect(status().isOk()));
        // CL: 8 left, at most 3 go on; EL: all 10 go on; SL does not carry
        assertThat(JsonPath.<List<Double>>read(balances, "$[?(@.code == 'CL')].carried")).containsExactly(3.0);
        assertThat(JsonPath.<List<Double>>read(balances, "$[?(@.code == 'CL')].entitled")).containsExactly(13.0);
        assertThat(JsonPath.<List<Double>>read(balances, "$[?(@.code == 'EL')].carried")).containsExactly(10.0);
        assertThat(JsonPath.<List<Double>>read(balances, "$[?(@.code == 'SL')].carried")).containsExactly(0.0);
        assertThat(JsonPath.<List<Double>>read(balances, "$[?(@.code == 'SL')].available")).containsExactly(10.0);
        // this plan's requests only
        mockMvc.perform(get("/api/leave/me/requests").header("Authorization", auth(emp[1]))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theReportingPersonApprovesAndNobodyAboveMeansApprovedAtOnce() throws Exception {
        String[] boss = person("MANAGER", null);
        String[] emp = person("EMPLOYEE", boss[0]);
        String[] peer = person("MANAGER", null);
        // no reporting person: approved at once
        apply(boss[1], "CL", workingDay(today.plusDays(1))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        String id = idOf(apply(emp[1], "CL", workingDay(today.plusDays(2))).andExpect(jsonPath("$.status").value("PENDING")));
        // only the person they report to, not another manager of the brand; an employee with people below them can too
        mockMvc.perform(post("/api/leave/requests/" + id + "/approve").header("Authorization", auth(peer[1]))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/leave/requests").header("Authorization", auth(peer[1]))).andExpect(jsonPath("$[*].id", not(hasItem(Integer.valueOf(id)))));
        mockMvc.perform(post("/api/leave/requests/" + id + "/approve").header("Authorization", auth(boss[1]))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // reporting to someone who is set to auto-approve those below them: approved at once
        json(put("/api/employees/" + boss[0]), owner, employeeJson("Boss Auto", boss[2], "MANAGER", null, true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.autoApproveReports").value(true));
        // 4 days on, not 3: when the last one was a Saturday, a day later would move back onto that same Saturday
        apply(emp[1], "CL", workingDay(today.plusDays(4))).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionNote").value(containsString("Approved automatically")));
    }

    @Test
    void approvalsFilterByDatesAndBrandCode() throws Exception {
        String[] boss = person("MANAGER", null);
        String[] emp = person("EMPLOYEE", boss[0]);
        LocalDate near = workingDay(today.plusDays(2));
        LocalDate far = workingDay(today.plusDays(30));
        apply(emp[1], "CL", near).andExpect(status().isCreated());
        apply(emp[1], "CL", far).andExpect(status().isCreated());
        mockMvc.perform(get("/api/leave/requests").param("from", near.toString()).param("to", near.toString()).header("Authorization", auth(boss[1])))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].fromDate").value(near.toString()));
        mockMvc.perform(get("/api/leave/requests").param("from", near.plusDays(1).toString()).header("Authorization", auth(boss[1])))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].fromDate").value(far.toString()));
        mockMvc.perform(get("/api/leave/requests").param("brandCode", "DW").header("Authorization", auth(boss[1]))).andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/leave/requests").param("brandCode", "LP").header("Authorization", auth(boss[1]))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void holidaysAreMappedToABrandAndOthersDoNotSeeThem() throws Exception {
        String year = String.valueOf(today.getYear());
        String[] emp = person("EMPLOYEE", null);
        json(post("/api/leave/restricted-holidays"), owner, "{\"date\":\"%s-03-10\",\"name\":\"No brand\"}".formatted(year))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Choose the brand of this holiday"));
        json(post("/api/leave/restricted-holidays"), owner, "{\"date\":\"%s-03-10\",\"name\":\"Other brand day\",\"brandId\":%d}".formatted(year, other))
                .andExpect(status().isCreated());
        json(post("/api/leave/office-holidays"), owner, "{\"date\":\"%s-03-11\",\"name\":\"DW closed\",\"brandId\":%d}".formatted(year, dw))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/leave/restricted-holidays").param("year", year).header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$[*].name", not(hasItem("Other brand day"))));
        mockMvc.perform(get("/api/leave/office-holidays").param("year", year).header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$[*].name", hasItem("DW closed")));
        mockMvc.perform(get("/api/leave/restricted-holidays").param("year", year).param("brandId", String.valueOf(dw)).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[*].name", not(hasItem("Other brand day"))));
        // a brand's admin changes only theirs
        String[] admin = person("ADMIN", null);
        json(post("/api/leave/office-holidays"), admin[1], "{\"date\":\"%s-03-12\",\"name\":\"Not mine\",\"brandId\":%d}".formatted(year, other))
                .andExpect(status().isForbidden());
    }

    @Test
    void plansFollowTheBrandAndOnlyOwnersAndAdminsChangeThem() throws Exception {
        String[] admin = person("ADMIN", null);
        String[] emp = person("EMPLOYEE", null);
        mockMvc.perform(get("/api/leave/plans").header("Authorization", auth(emp[1]))).andExpect(status().isForbidden());
        // a brand's admin sees and changes only their brand's
        mockMvc.perform(get("/api/leave/plans").header("Authorization", auth(admin[1])))
                .andExpect(jsonPath("$.brands.length()").value(1)).andExpect(jsonPath("$.brands[0].brandCode").value("DW"));
        json(post("/api/leave/plans"), admin[1], planJson(other, today, today.plusDays(100), line("CL", 5, false, null))).andExpect(status().isForbidden());
        // owner: every brand and the group-level people; a brand with no plan is flagged, and can start one
        mockMvc.perform(get("/api/leave/plans").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.brands[?(@.brandCode == 'LP')].canAdd").value(hasItem(true)))
                .andExpect(jsonPath("$.brands[?(@.brandName == 'Group (no brand)')]").isNotEmpty());
        mockMvc.perform(get("/api/leave/plans/alerts").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.brandCode == 'LP')].validUntil").value(hasItem(nullValue())));
        // the days are the plan's, not the leave type's
        Map<String, Object> current = currentPlan(dw);
        json(put("/api/leave/plans/" + current.get("id")), owner, planJson(dw, today.minusDays(120), today.plusDays(120),
                line("CL", 15, false, null), line("LWP", null, false, null))).andExpect(status().isOk());
        balance(emp[1], "CL", 15.0);
        // new dates cannot strand applied-for leave
        LocalDate day = workingDay(today.plusDays(50));
        apply(emp[1], "CL", day).andExpect(status().isCreated());
        json(put("/api/leave/plans/" + current.get("id")), owner, planJson(dw, today.minusDays(120), today.plusDays(10), line("CL", 15, false, null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value(startsWith("Leave has been applied for")));
    }

    // ---- helpers ----

    private void balance(String token, String code, double expected) throws Exception {
        List<Double> values = JsonPath.read(body(mockMvc.perform(get("/api/leave/me/balances").header("Authorization", auth(token)))),
                "$[?(@.code == '" + code + "')].entitled");
        assertThat(values).containsExactly(expected);
    }

    private Map<String, Object> currentPlan(long brandId) throws Exception {
        List<Map<String, Object>> found = JsonPath.read(body(mockMvc.perform(get("/api/leave/plans").header("Authorization", auth(owner)))),
                "$.brands[?(@.brandId == " + brandId + ")].plans[?(@.state == 'CURRENT')]");
        return found.getFirst();
    }

    /** A Monday-to-Saturday date on or before this one. */
    private static LocalDate workingDay(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SUNDAY ? day.minusDays(1) : day;
    }

    private String line(String code, Integer days, boolean carry, Integer max) {
        return "{\"leaveTypeId\":%d,\"days\":%s,\"carryForward\":%s,\"maxCarryDays\":%s}".formatted(typeIds.get(code), days, carry, max);
    }

    private static String planJson(long brandId, LocalDate from, LocalDate until, String... lines) {
        return "{\"brandId\":%d,\"validFrom\":\"%s\",\"validUntil\":\"%s\",\"lines\":[%s]}".formatted(brandId, from, until, String.join(",", lines));
    }

    private String employeeJson(String name, String userId, String role, String reportsTo, boolean autoApprove) {
        return """
                {"name":"%s","userId":"%s","role":"%s","brandId":%d,"reportsToId":%s,"autoApproveReports":%s,
                 "phone":"+91 98470 00000","joinedDate":"2026-01-01"}""".formatted(name, userId, role, dw, reportsTo, autoApprove);
    }

    /** A new person in DW with their own PIN: {id, token, userId}. */
    private String[] person(String role, String reportsTo) throws Exception {
        String userId = "lp" + (++seq);
        String json = """
                {"name":"Plan %d","userId":"%s","password":"ab@123","role":"%s","brandId":%d,"reportsToId":%s,
                 "phone":"+91 98470 00000","joinedDate":"2026-01-01"}""".formatted(seq, userId, role, dw, reportsTo);
        String id = idOf(json(post("/api/employees"), owner, json).andExpect(status().isCreated()));
        return new String[]{id, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456")), userId};
    }

    private ResultActions apply(String token, String code, LocalDate day) throws Exception {
        return json(post("/api/leave/me/requests"), token, "{\"leaveTypeId\":%d,\"fromDate\":\"%s\",\"toDate\":\"%s\",\"halfDay\":false}".formatted(typeIds.get(code), day, day));
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private ResultActions changePin(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
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
