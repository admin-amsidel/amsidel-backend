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
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Yearly payroll set-up, with the amounts of a real September 2025 payslip. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=payroll")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayrollTest {

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private long dw;
    private Map<String, Integer> components;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.get(0);
        String list = body(mockMvc.perform(get("/api/payroll/components").header("Authorization", auth(owner)))
                .andExpect(status().isOk()));
        List<Map<String, Object>> all = JsonPath.read(list, "$");
        components = all.stream().collect(Collectors.toMap(c -> (String) c.get("code"), c -> (Integer) c.get("id")));
    }

    @Test
    void theStandardComponentsAreThere() throws Exception {
        mockMvc.perform(get("/api/payroll/components").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[*].code").value(hasItems("BASIC", "DA", "HRA", "LTA", "SPECIAL", "EPF", "LWF", "TDS",
                        "PT", "FUEL", "PHONE")))
                .andExpect(jsonPath("$[?(@.code == 'LTA')].name").value(hasItem("Leave Travel Allowance")))
                .andExpect(jsonPath("$[0].code").value("BASIC"))
                .andExpect(jsonPath("$[5].code").value("EPF"))
                .andExpect(jsonPath("$[?(@.code == 'EPF')].kind").value(hasItem("DEDUCTION")))
                .andExpect(jsonPath("$[?(@.code == 'FUEL')].kind").value(hasItem("REIMBURSEMENT")));
    }

    @Test
    void aSalaryForTheYearWithMonthlyAndYearlyTotals() throws Exception {
        String emp = employee("EMPLOYEE", dw, "2025-09-09")[0];
        // the payslip: gross 2,38,652; deductions 28,107; reimbursements 4,400; net 2,14,945
        save(owner, emp, 2025, "2025-09-09", payslip()).andExpect(status().isOk())
                .andExpect(jsonPath("$.fyLabel").value("FY 2025-26"))
                .andExpect(jsonPath("$.set").value(true))
                .andExpect(jsonPath("$.totals.monthlyGross").value(238652.00))
                .andExpect(jsonPath("$.totals.monthlyDeductions").value(28107.00))
                .andExpect(jsonPath("$.totals.monthlyReimbursements").value(4400.00))
                .andExpect(jsonPath("$.totals.monthlyNet").value(214945.00))
                .andExpect(jsonPath("$.totals.annualGross").value(2863824.00))
                .andExpect(jsonPath("$.totals.annualNet").value(2579340.00))
                // joined in September: paid September to March in this financial year
                .andExpect(jsonPath("$.totals.monthsInYear").value(7))
                .andExpect(jsonPath("$.totals.yearNet").value(1504615.00))
                .andExpect(jsonPath("$.lines[?(@.code == 'HRA')].annual").value(hasItem(469332.00)));

        mockMvc.perform(get("/api/payroll/salaries").param("fy", "2025").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.rows[?(@.employee.id == " + emp + ")].set").value(hasItem(true)))
                .andExpect(jsonPath("$.rows[?(@.employee.id == " + emp + ")].totals.monthlyNet").value(hasItem(214945.00)))
                .andExpect(jsonPath("$.withSalary").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.years").value(hasItem(2025)));

        // a new year starts empty, with the year's 1 April
        mockMvc.perform(get("/api/payroll/salaries/" + emp).param("fy", "2026").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.set").value(false))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-04-01"))
                .andExpect(jsonPath("$.totals.monthlyNet").value(0.0));

        // 0 drops a line; a change keeps the rest
        save(owner, emp, 2025, "2025-09-09", "[" + line("BASIC", "100000") + "," + line("TDS", "0") + "]")
                .andExpect(jsonPath("$.totals.monthlyGross").value(100000.00))
                .andExpect(jsonPath("$.totals.monthlyDeductions").value(0.0));

        mockMvc.perform(delete("/api/payroll/salaries/" + emp).param("fy", "2025").header("Authorization", auth(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/payroll/salaries/" + emp).param("fy", "2025").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.set").value(false));
    }

    @Test
    void checksAndWhoMay() throws Exception {
        String[] emp = employee("EMPLOYEE", dw, "2025-04-01");
        save(owner, emp[0], 2025, "2026-04-01", payslip()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(startsWith("Effective from must be inside FY 2025-26")));
        save(owner, emp[0], 2025, "2025-04-01", "[" + line("BASIC", "0") + "]").andExpect(status().isBadRequest());
        save(owner, emp[0], 2025, "2025-04-01", "[" + line("BASIC", "-5") + "]").andExpect(status().isBadRequest());

        // an employee has no Payroll menu
        mockMvc.perform(get("/api/payroll/salaries").header("Authorization", auth(emp[1]))).andExpect(status().isForbidden());

        // an admin of another brand: not this brand's people, and never their own salary
        long other = idOfLong(json(post("/api/brands"), owner, "{\"code\":\"PX\",\"name\":\"Payroll Test Brand\"}"));
        String[] admin = employee("ADMIN", other, "2025-04-01");
        save(admin[1], emp[0], 2025, "2025-04-01", payslip()).andExpect(status().isForbidden());
        save(admin[1], admin[0], 2025, "2025-04-01", payslip()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Your own pay is set by someone else"));
        String rows = body(mockMvc.perform(get("/api/payroll/salaries").param("fy", "2025").header("Authorization", auth(admin[1])))
                .andExpect(status().isOk()));
        List<String> brands = JsonPath.read(rows, "$.rows[*].employee.brandCode");
        org.assertj.core.api.Assertions.assertThat(brands).containsOnly("PX");
        // the owner sees and sets everyone's
        save(owner, admin[0], 2025, "2025-04-01", payslip()).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.monthsInYear").value(12));
    }

    @Test
    void componentsCanBeAddedAndRetired() throws Exception {
        String body = "{\"code\":\"bonus\",\"name\":\"Performance bonus\",\"kind\":\"EARNING\",\"sortOrder\":60,\"active\":true}";
        String id = String.valueOf((Object) JsonPath.read(body(json(post("/api/payroll/components"), owner, body)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("BONUS"))), "$.id"));
        json(post("/api/payroll/components"), owner, body).andExpect(status().isConflict());

        String[] emp = employee("EMPLOYEE", dw, "2025-04-01");
        save(owner, emp[0], 2025, "2025-04-01", "[{\"componentId\":" + id + ",\"monthly\":5000}]").andExpect(status().isOk());
        // used: cannot be deleted or turned into a deduction, but can be retired
        mockMvc.perform(delete("/api/payroll/components/" + id).header("Authorization", auth(owner))).andExpect(status().isConflict());
        json(put("/api/payroll/components/" + id), owner, body.replace("EARNING", "DEDUCTION")).andExpect(status().isConflict());
        json(put("/api/payroll/components/" + id), owner, body.replace("true", "false")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.inUse").value(true));
        // a retired component still shows on the salary that uses it
        mockMvc.perform(get("/api/payroll/salaries/" + emp[0]).param("fy", "2025").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.lines[?(@.code == 'BONUS')].active").value(hasItem(false)))
                .andExpect(jsonPath("$.lines[?(@.code == 'BONUS')].monthly").value(hasItem(5000.00)));
        // an employee cannot change components
        json(post("/api/payroll/components"), emp[1], body.replace("bonus", "x")).andExpect(status().isForbidden());
    }

    @Test
    void incomeTaxByRegimeOrTypedInBySomeoneAllowed() throws Exception {
        String[] hr = employee("EMPLOYEE", dw, "2025-04-01", "8.2");
        String emp = employee("EMPLOYEE", dw, "2025-04-01")[0];
        String lines = "[" + line("BASIC", "150000") + "," + line("PT", "200") + "," + line("TDS", "5000") + "]";
        // payroll staff without the Manual income tax access: the new regime works it out (the typed 5,000 is not kept)
        // 18 lakh a year - 75,000 = 17.25 lakh: 60,000 + 60,000 + 25,000 = 1,45,000 + cess 5,800 = 1,50,800 = 12,567 a month
        json(put("/api/payroll/salaries/" + emp + "?fy=2025"), hr[1], "{\"effectiveFrom\":\"2025-04-01\",\"lines\":%s}".formatted(lines))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tax.regime").value("NEW"))
                .andExpect(jsonPath("$.tax.canManual").value(false))
                .andExpect(jsonPath("$.tax.chosen.annualTax").value(150800.0))
                .andExpect(jsonPath("$.lines[?(@.code == 'TDS')].monthly").value(hasItem(12567.0)));
        json(put("/api/payroll/salaries/" + emp + "?fy=2025"), hr[1],
                "{\"effectiveFrom\":\"2025-04-01\",\"taxRegime\":\"MANUAL\",\"lines\":%s}".formatted(lines))
                .andExpect(status().isForbidden());

        // the old regime with professional tax and the declared deductions: 15,47,600 taxable = 2,76,780 + cess = 2,87,850 a year
        json(put("/api/payroll/salaries/" + emp + "?fy=2025"), hr[1],
                "{\"effectiveFrom\":\"2025-04-01\",\"taxRegime\":\"OLD\",\"taxDeclared\":200000,\"lines\":%s}".formatted(lines))
                .andExpect(jsonPath("$.tax.regime").value("OLD"))
                .andExpect(jsonPath("$.tax.declared").value(200000.0))
                .andExpect(jsonPath("$.tax.chosen.taxable").value(1547600.0))
                .andExpect(jsonPath("$.tax.chosen.annualTax").value(287850.0))
                .andExpect(jsonPath("$.tax.newRegime.annualTax").value(150800.0))
                .andExpect(jsonPath("$.lines[?(@.code == 'TDS')].monthly").value(hasItem(23988.0)));

        // the owner may type it; then payroll staff may change the salary but not the tax
        json(put("/api/payroll/salaries/" + emp + "?fy=2025"), owner,
                "{\"effectiveFrom\":\"2025-04-01\",\"taxRegime\":\"MANUAL\",\"lines\":%s}".formatted(lines))
                .andExpect(jsonPath("$.tax.regime").value("MANUAL"))
                .andExpect(jsonPath("$.tax.chosen").doesNotExist())
                .andExpect(jsonPath("$.lines[?(@.code == 'TDS')].monthly").value(hasItem(5000.0)));
        String moreTax = "[" + line("BASIC", "150000") + "," + line("PT", "200") + "," + line("TDS", "9000") + "]";
        json(put("/api/payroll/salaries/" + emp + "?fy=2025"), hr[1], "{\"effectiveFrom\":\"2025-04-01\",\"lines\":%s}".formatted(moreTax))
                .andExpect(status().isForbidden());
        String raise = "[" + line("BASIC", "160000") + "," + line("PT", "200") + "," + line("TDS", "5000") + "]";
        json(put("/api/payroll/salaries/" + emp + "?fy=2025"), hr[1], "{\"effectiveFrom\":\"2025-04-01\",\"lines\":%s}".formatted(raise))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tax.regime").value("MANUAL"));

        // the preview while typing: both regimes
        json(post("/api/payroll/tax-preview?fy=2025"), hr[1], "{\"effectiveFrom\":\"2025-04-01\",\"monthlyGross\":150000,\"monthlyProfessionalTax\":200,\"declared\":200000}")
                .andExpect(jsonPath("$.NEW.annualTax").value(150800.0)).andExpect(jsonPath("$.OLD.annualTax").value(287850.0));
    }

    @Test
    void hrGetsThePayrollMenus() throws Exception {
        String[] hr = employee("EMPLOYEE", dw, "2025-04-01", "8.2");
        mockMvc.perform(get("/api/payroll/salaries").header("Authorization", auth(hr[1]))).andExpect(status().isOk());
        mockMvc.perform(get("/api/payroll/components").header("Authorization", auth(hr[1]))).andExpect(status().isOk());
    }

    // ---- helpers ----

    /** The September 2025 payslip as monthly amounts. */
    private String payslip() {
        return "[" + String.join(",",
                line("BASIC", "97778"), line("DA", "24444"), line("HRA", "39111"), line("LTA", "3667"), line("SPECIAL", "73652"),
                line("EPF", "1800"), line("LWF", "100"), line("TDS", "24957"), line("PT", "1250"),
                line("FUEL", "2933"), line("PHONE", "1467")) + "]";
    }

    private String line(String code, String monthly) {
        return "{\"componentId\":%d,\"monthly\":%s}".formatted(components.get(code), monthly);
    }

    private ResultActions save(String token, String employeeId, int fy, String from, String lines) throws Exception {
        return json(put("/api/payroll/salaries/" + employeeId + "?fy=" + fy), token,
                "{\"effectiveFrom\":\"%s\",\"notes\":\"\",\"lines\":%s}".formatted(from, lines));
    }

    private String[] employee(String role, long brand, String joined) throws Exception {
        return employee(role, brand, joined, null);
    }

    /** {id, token}; levelCode picks the designation. */
    private String[] employee(String role, long brand, String joined, String levelCode) throws Exception {
        String userId = "pr.emp" + (++seq);
        String designation = "null";
        if (levelCode != null) {
            List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/designations").header("Authorization", auth(owner)))),
                    "$[?(@.levelCode == '" + levelCode + "')].id");
            designation = String.valueOf(ids.get(0));
        }
        String json = """
                {"name":"Pay %d","userId":"%s","password":"ab@123","role":"%s","brandId":%d,"designationId":%s,
                 "phone":"+91 98470 00000","joinedDate":"%s"}
                """.formatted(seq, userId, role, brand, designation, joined);
        String id = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated())), "$.id"));
        return new String[]{id, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token,
                               String body) throws Exception {
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

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String token(ResultActions result) throws Exception {
        return JsonPath.read(body(result), "$.token");
    }

    private static long idOfLong(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
