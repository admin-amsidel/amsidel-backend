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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Projects: the team and money of a customer's job, its tasks with dates and bills, its quotations and invoices and
 * their payments, the history; settled projects are locked for staff; only owners and admins delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=projects")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private String staff;
    private String staffId;
    private long dw;
    private String customer;
    private String other;
    private String item;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.getFirst();
        // a manager of DW with the project and sales menus (not an admin)
        staffId = idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Priya Sales\",\"userId\":\"pj.priya\",\"password\":\"ab@123\",\"role\":\"MANAGER\",\"brandId\":%d,\"phone\":\"9847000001\",\"joinedDate\":\"2026-01-05\"}"
                        .formatted(dw))).andExpect(status().isCreated()));
        mockMvc.perform(put("/api/menu-access/EMPLOYEE/" + staffId).header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkpoints\":[\"11500\",\"11302\",\"11303\",\"11301\"]}")).andExpect(status().isOk());
        staff = token(changePin(token(login("pj.priya", "ab@123")), "ab@123", "cd@456"));
        customer = idOf(json(post("/api/trade/customers"), owner, "{\"brandId\":%d,\"name\":\"Varghese Villa\",\"phone\":\"9847033333\"}".formatted(dw)));
        other = idOf(json(post("/api/trade/customers"), owner, "{\"brandId\":%d,\"name\":\"Someone Else\",\"phone\":\"9847044444\"}".formatted(dw)));
        List<Integer> types = JsonPath.read(body(mockMvc.perform(get("/api/trade/types").param("brandId", String.valueOf(dw))
                .header("Authorization", auth(owner)))), "$[?(@.code == 'UPVC')].id");
        item = idOf(json(post("/api/trade/items"), owner, """
                {"brandId":%d,"typeId":%d,"code":"UPVC-CW-01","name":"uPVC casement window","supplierPrice":4000,"mrp":9000,
                 "showroomPrice":8000,"minSellPrice":7000,"openingStock":20}
                """.formatted(dw, types.getFirst())));
    }

    @Test
    void aProjectFromQuotationToSettled() throws Exception {
        String project = idOf(json(post("/api/trade/projects"), staff, """
                {"name":"Villa windows","customerId":%s,"description":"All windows of the new villa, ground and first floor",
                 "ownerId":%s,"memberIds":[%s],"startDate":"%s","endDate":"%s","estimatedAmount":50000}
                """.formatted(customer, staffId, staffId, TODAY, TODAY.plusDays(30))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", matchesPattern("DWPRJ-\\d{6}-\\d{3}")))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.owner.name").value("Priya Sales"))
                .andExpect(jsonPath("$.createdByName").value("Priya Sales"))
                .andExpect(jsonPath("$.money.toPay").value(50000.0)));

        // a task, then a quotation and an invoice of the project
        json(post("/api/trade/projects/" + project + "/tasks"), staff,
                "{\"title\":\"Measure all openings\",\"regDate\":\"%s\",\"assigneeId\":%s}".formatted(TODAY, staffId))
                .andExpect(jsonPath("$.tasks[0].status").value("QUEUED"));
        json(post("/api/trade/sales"), staff, """
                {"kind":"QUOTATION","customerId":%s,"projectId":%s,"docDate":"%s","gstRate":0,"lines":[{"itemId":%s,"name":"w","quantity":6,"rate":8000}]}
                """.formatted(other, project, TODAY, item)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("is for Varghese Villa")));
        String quote = idOf(json(post("/api/trade/sales"), staff, """
                {"kind":"QUOTATION","customerId":%s,"projectId":%s,"docDate":"%s","gstRate":0,"lines":[{"itemId":%s,"name":"w","quantity":6,"rate":8000}]}
                """.formatted(customer, project, TODAY, item)).andExpect(jsonPath("$.projectCode", startsWith("DWPRJ-"))));
        String invoice = idOf(mockMvc.perform(post("/api/trade/sales/" + quote + "/invoice").header("Authorization", auth(staff)))
                .andExpect(jsonPath("$.projectId").value(Integer.parseInt(project))));
        mockMvc.perform(post("/api/trade/sales/" + invoice + "/issue").header("Authorization", auth(staff))).andExpect(status().isOk());
        json(post("/api/trade/sales/" + invoice + "/payments"), staff,
                "{\"paidOn\":\"%s\",\"amount\":20000,\"mode\":\"UPI\",\"transactionId\":\"UPI-1\",\"remarks\":\"Advance\"}".formatted(TODAY));

        // the task follows the invoice; starting it sets its start date
        String view = body(mockMvc.perform(get("/api/trade/projects/" + project).header("Authorization", auth(staff))));
        Integer taskId = JsonPath.read(view, "$.tasks[0].id");
        json(put("/api/trade/project-tasks/" + taskId), staff,
                "{\"title\":\"Measure all openings\",\"regDate\":\"%s\",\"assigneeId\":%s,\"billId\":%s,\"remarks\":\"Site ready\"}".formatted(TODAY, staffId, invoice))
                .andExpect(jsonPath("$.tasks[0].billNumber", startsWith("Invoice DWSLD-")));
        json(post("/api/trade/project-tasks/" + taskId + "/status"), staff, "{\"status\":\"IN_PROGRESS\"}")
                .andExpect(jsonPath("$.tasks[0].startDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.tasks[0].history[0].details", containsString("Invoice DWSLD-")));

        // money, bills, payments and the whole story are on the project
        mockMvc.perform(get("/api/trade/projects/" + project).header("Authorization", auth(staff)))
                .andExpect(jsonPath("$.money.quoted").value(48000.0))
                .andExpect(jsonPath("$.money.billed").value(48000.0))
                .andExpect(jsonPath("$.money.paid").value(20000.0))
                .andExpect(jsonPath("$.money.toPay").value(48000.0))
                .andExpect(jsonPath("$.money.due").value(28000.0))
                .andExpect(jsonPath("$.bills.length()").value(2))
                .andExpect(jsonPath("$.payments[0].transactionId").value("UPI-1"))
                .andExpect(jsonPath("$.history[*].subjectType", hasItems("PROJECT", "PROJECT_TASK", "QUOTATION", "INVOICE")))
                .andExpect(jsonPath("$.history[*].action", hasItems("PAYMENT", "ISSUED", "INVOICED", "STATUS")));

        // staff cannot settle with money due; the owner can; then staff can only comment
        mockMvc.perform(post("/api/trade/projects/" + project + "/settle").header("Authorization", auth(staff))).andExpect(status().isConflict());
        json(post("/api/trade/projects/" + project + "/comments"), staff, "{\"text\":\"Customer will pay the rest after the festival\"}")
                .andExpect(jsonPath("$.history[0].action").value("COMMENT"));
        mockMvc.perform(post("/api/trade/projects/" + project + "/settle").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.settled").value(true)).andExpect(jsonPath("$.status").value("COMPLETED"));
        json(put("/api/trade/projects/" + project), staff, "{\"name\":\"Changed\",\"customerId\":%s}".formatted(customer))
                .andExpect(status().isForbidden());
        json(post("/api/trade/projects/" + project + "/tasks"), staff, "{\"title\":\"More\",\"regDate\":\"%s\"}".formatted(TODAY))
                .andExpect(status().isForbidden());
        json(post("/api/trade/projects/" + project + "/comments"), staff, "{\"text\":\"Paid in full on 1 Nov\"}").andExpect(status().isOk());
        mockMvc.perform(get("/api/trade/projects/" + project).header("Authorization", auth(staff)))
                .andExpect(jsonPath("$.editable").value(false));
        // admins and owners may still change it, reopen it and delete it; staff may not delete
        json(put("/api/trade/projects/" + project), owner, """
                {"name":"Villa windows","customerId":%s,"finalAmount":46000,"ownerId":%s,"memberIds":[%s]}
                """.formatted(customer, staffId, staffId)).andExpect(jsonPath("$.money.toPay").value(46000.0))
                .andExpect(jsonPath("$.history[0].details", containsString("Finalized — → ₹46,000.00")));
        mockMvc.perform(post("/api/trade/projects/" + project + "/reopen").header("Authorization", auth(staff))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/trade/projects/" + project + "/reopen").header("Authorization", auth(owner))).andExpect(jsonPath("$.settled").value(false));
        mockMvc.perform(delete("/api/trade/projects/" + project).header("Authorization", auth(staff))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/trade/projects/" + project).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        // its bills stay, without the project
        mockMvc.perform(get("/api/trade/sales/" + invoice).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.projectId").value(nullValue()));
    }

    @Test
    void onlyOwnersAndAdminsDeleteBills() throws Exception {
        String draft = idOf(json(post("/api/trade/sales"), staff, """
                {"kind":"QUOTATION","customerId":%s,"docDate":"%s","gstRate":18,"lines":[{"name":"Survey","quantity":1,"rate":500}]}
                """.formatted(customer, TODAY)).andExpect(jsonPath("$.canDelete").value(false)));
        mockMvc.perform(delete("/api/trade/sales/" + draft).header("Authorization", auth(staff))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/trade/sales/" + draft).header("Authorization", auth(owner))).andExpect(jsonPath("$.canDelete").value(true));
        mockMvc.perform(delete("/api/trade/sales/" + draft).header("Authorization", auth(owner))).andExpect(status().isNoContent());
    }

    // ---- helpers ----

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
