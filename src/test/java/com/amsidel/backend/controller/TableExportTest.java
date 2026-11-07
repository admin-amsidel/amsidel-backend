/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Export to Excel on the list screens. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=tableexport")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TableExportTest {

    /** The sheet's header row; the rows follow it. */
    private static final int HEADER = 3;

    @Autowired
    private MockMvc mockMvc;

    private String owner;

    @BeforeAll
    void setUp() throws Exception {
        String first = JsonPath.read(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"owner\",\"password\":\"Ams@2026\"}")).andReturn().getResponse().getContentAsString(), "$.token");
        owner = JsonPath.read(mockMvc.perform(post("/api/auth/change-password").header("Authorization", "asd " + first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Ams@2026\",\"newPassword\":\"Own#2026\"}")).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions export(String body) throws Exception {
        return mockMvc.perform(post("/api/exports/table").header("Authorization", "asd " + owner).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void theRowsComeOutAsAnExcelFile() throws Exception {
        byte[] file = export("""
                {"title":"Team & Lead list","columns":["Name","Amount","Note"],
                 "rows":[["Asha",1250,null],["Ravi",99.5,"=1+1"]]}
                """)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("spreadsheetml")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("Team-Lead-list_")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Team & Lead list");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("2 rows").contains("owner");
            assertThat(sheet.getRow(HEADER).getCell(1).getStringCellValue()).isEqualTo("Amount");
            Row asha = sheet.getRow(HEADER + 1);
            assertThat(asha.getCell(0).getStringCellValue()).isEqualTo("Asha");
            assertThat(asha.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(asha.getCell(1).getNumericCellValue()).isEqualTo(1250);
            assertThat(asha.getCell(2)).isNull();
            Row ravi = sheet.getRow(HEADER + 2);
            assertThat(ravi.getCell(1).getNumericCellValue()).isEqualTo(99.5);
            // what looks like a formula is only text
            assertThat(ravi.getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(ravi.getCell(2).getStringCellValue()).isEqualTo("=1+1");
        }
    }

    @Test
    void anEmptyListAndVeryLongTextStillGiveAFile() throws Exception {
        export("""
                {"title":"Nothing","columns":["A"],"rows":[]}
                """).andExpect(status().isOk());
        byte[] file = export("""
                {"title":"Long","columns":["A"],"rows":[["%s"]]}
                """.formatted("x".repeat(40_000))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(book.getSheetAt(0).getRow(HEADER + 1).getCell(0).getStringCellValue()).hasSize(32_000);
        }
    }

    @Test
    void aBadListIsRefused() throws Exception {
        // a row of another shape
        export("""
                {"title":"Bad","columns":["A","B"],"rows":[["only one"]]}
                """).andExpect(status().isBadRequest());
        // no heading, no title
        export("""
                {"title":"Bad","columns":[],"rows":[]}
                """).andExpect(status().isBadRequest());
        export("""
                {"title":" ","columns":["A"],"rows":[]}
                """).andExpect(status().isBadRequest());
    }

    private int seq;

    /** Someone with their own ticks: only these count. */
    private String person(String role, String... ticks) throws Exception {
        String userId = "xl." + (++seq);
        java.util.List<Integer> brands = JsonPath.read(mockMvc.perform(get("/api/brands").header("Authorization", "asd " + owner)).andReturn().getResponse().getContentAsString(), "$[*].id");
        String created = mockMvc.perform(post("/api/employees").header("Authorization", "asd " + owner).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Export Person %d","userId":"%s","password":"ab@123","role":"%s","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, role, brands.getFirst()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        if (ticks.length > 0) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/menu-access/EMPLOYEE/" + JsonPath.read(created, "$.id"))
                    .header("Authorization", "asd " + owner).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"checkpoints\":[\"" + String.join("\",\"", ticks) + "\"]}")).andExpect(status().isOk());
        }
        String first = JsonPath.read(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"ab@123\"}".formatted(userId))).andReturn().getResponse().getContentAsString(), "$.token");
        return JsonPath.read(mockMvc.perform(post("/api/auth/change-password").header("Authorization", "asd " + first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"ab@123\",\"newPassword\":\"cd@456\"}")).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions exportAs(String token) throws Exception {
        return mockMvc.perform(post("/api/exports/table").header("Authorization", "asd " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"X\",\"columns\":[\"A\"],\"rows\":[]}"));
    }

    private ResultActions attendanceFile(String token) throws Exception {
        return mockMvc.perform(get("/api/attendance/export").header("Authorization", "asd " + token)
                .param("from", "2026-01-01").param("to", "2026-01-31"));
    }

    @Test
    void theTickDecidesWhoMayExportOnEveryKindOfExport() throws Exception {
        // the page, not the tick: no file of either kind, and the portal is told not to show the button
        String without = person("EMPLOYEE", "1001");
        exportAs(without).andExpect(status().isForbidden());
        attendanceFile(without).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/access").header("Authorization", "asd " + without))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.codes", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("EXPORT_EXCEL"))));

        // the whole Administration menu does not bring it either: it has its own tick
        String admins = person("EMPLOYEE", "1001", "9000");
        exportAs(admins).andExpect(status().isForbidden());

        // with it: both
        String with = person("EMPLOYEE", "1001", "9022");
        exportAs(with).andExpect(status().isOk());
        attendanceFile(with).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/access").header("Authorization", "asd " + with))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.codes", org.hamcrest.Matchers.hasItem("EXPORT_EXCEL")));

        // the tick alone does not open a page's own file
        String tickOnly = person("EMPLOYEE", "9022", "2001");
        exportAs(tickOnly).andExpect(status().isOk());
        attendanceFile(tickOnly).andExpect(status().isForbidden());

        // owners always may; it can be ticked on the Menu access page
        exportAs(owner).andExpect(status().isOk());
        mockMvc.perform(get("/api/menu-access/menus").header("Authorization", "asd " + owner))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[?(@.code == 'ADMIN')].children[?(@.code == 'EXPORT_EXCEL')].checkpoint", org.hamcrest.Matchers.hasItem(9022)));
    }

    @Test
    void oneMustBeSignedIn() throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(mockMvc.perform(post("/api/exports/table").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"X\",\"columns\":[\"A\"],\"rows\":[]}")).andReturn().getResponse().getStatus() / 100 == 4);
    }
}
