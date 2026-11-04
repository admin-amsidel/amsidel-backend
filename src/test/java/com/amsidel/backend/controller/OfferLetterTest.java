/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.service.MenuAccessService;
import com.jayway.jsonpath.JsonPath;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Offer letters: for owners, admins and HR; locked once issued unless the person has the tick; each brand's own. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=offerletters")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfferLetterTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MenuAccessService menuAccess;
    @Autowired
    private BrandRepository brands;

    private String owner;
    private String hr;
    private String hrId;
    private String employee;
    private String otherAdmin;
    private long dw;
    private long other;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> dwIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = dwIds.get(0);
        other = ((Number) JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"OB\",\"name\":\"Other Brand\"}")), "$.id")).longValue();
        List<Integer> hrLevel = JsonPath.read(body(mockMvc.perform(get("/api/designations").header("Authorization", auth(owner)))), "$[?(@.levelCode == '8.2')].id");
        hrId = person("Hema HR", "ol.hr", "EMPLOYEE", dw, hrLevel.get(0));
        hr = token(changePin(token(login("ol.hr", "ab@123")), "ab@123", "cd@456"));
        person("Eby Employee", "ol.emp", "EMPLOYEE", dw, null);
        employee = token(changePin(token(login("ol.emp", "ab@123")), "ab@123", "cd@456"));
        person("Olga Admin", "ol.adm", "ADMIN", other, null);
        otherAdmin = token(changePin(token(login("ol.adm", "ab@123")), "ab@123", "cd@456"));
    }

    @Test
    void forOwnersAdminsAndHrOnly() throws Exception {
        mockMvc.perform(get("/api/offer-letters").header("Authorization", auth(employee))).andExpect(status().isForbidden());
        // HR has the page through their designation, but not the ticks for issued letters
        mockMvc.perform(get("/api/offer-letters").header("Authorization", auth(hr))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canChangeIssued").value(false)).andExpect(jsonPath("$.canDeleteIssued").value(false));
        mockMvc.perform(get("/api/offer-letters").header("Authorization", auth(otherAdmin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canChangeIssued").value(true));
        mockMvc.perform(get("/api/offer-letters").header("Authorization", auth(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canDeleteIssued").value(true));
    }

    @Test
    void aDraftIsFreeThenIssuingChecksItAndGivesANumber() throws Exception {
        String id = idOf(json(post("/api/offer-letters"), hr, "{\"brandId\":%d,\"candidateName\":\"Riya Rao\"}".formatted(dw)).andExpect(status().isCreated()));
        json(post("/api/offer-letters/" + id + "/issue"), hr, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", startsWith("Before issuing, fill in: issued date, company name")));
        // the split has to come to the package
        json(put("/api/offer-letters/" + id), hr, letter("Riya Rao", "2026-11-04", "2026-11-16", "600000", "[{\"name\":\"Basic\",\"annualAmount\":500000}]")).andExpect(status().isOk());
        json(post("/api/offer-letters/" + id + "/issue"), hr, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The package split comes to 500000 but the annual package is 600000"));
        json(put("/api/offer-letters/" + id), hr, letter("Riya Rao", "2026-11-04", "2026-11-16", "600000", SPLIT).replace("\"startDate\":\"2026-11-16\"", "\"startDate\":\"2026-11-01\"")).andExpect(status().isOk());
        json(post("/api/offer-letters/" + id + "/issue"), hr, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The start date cannot be before the joining date"));
        json(put("/api/offer-letters/" + id), hr, letter("Riya Rao", "2026-11-04", "2026-11-16", "600000", SPLIT)).andExpect(status().isOk());

        json(post("/api/offer-letters/" + id + "/issue"), hr, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.letterNumber", startsWith("DW/OL/2026/")));
        json(post("/api/offer-letters/" + id + "/issue"), hr, "{}").andExpect(status().isConflict());

        // numbers go up within the brand
        String first = JsonPath.read(body(mockMvc.perform(get("/api/offer-letters/" + id).header("Authorization", auth(hr)))), "$.letterNumber");
        String second = issued("Sam Sen");
        String next = JsonPath.read(body(mockMvc.perform(get("/api/offer-letters/" + second).header("Authorization", auth(hr)))), "$.letterNumber");
        assertThat(Integer.parseInt(next.substring(next.lastIndexOf('/') + 1))).isEqualTo(Integer.parseInt(first.substring(first.lastIndexOf('/') + 1)) + 1);
    }

    @Test
    void anIssuedLetterIsLockedUnlessThePersonHasTheTick() throws Exception {
        String id = issued("Lena Lock");
        String changed = letter("Lena Changed", "2026-11-04", "2026-11-16", "600000", SPLIT);
        json(put("/api/offer-letters/" + id), hr, changed).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("This offer letter is issued and cannot be changed"));
        mockMvc.perform(delete("/api/offer-letters/" + id).header("Authorization", auth(hr))).andExpect(status().isForbidden());
        byte[] before = pdf(hr, id);

        // with the tick: changed, recorded, the PDF made again; it must stay complete and in its brand
        menuAccess.save(AccessSubject.EMPLOYEE, hrId, List.of("4004", "4005"));
        try {
            json(put("/api/offer-letters/" + id), hr, changed.replace("\"contactEmail\":\"hr@amsidel.com\"", "\"contactEmail\":null")).andExpect(status().isBadRequest());
            json(put("/api/offer-letters/" + id), hr, changed.replace("\"brandId\":" + dw, "\"brandId\":" + other)).andExpect(status().isBadRequest());
            json(put("/api/offer-letters/" + id), hr, changed).andExpect(status().isOk()).andExpect(jsonPath("$.candidateName").value("Lena Changed"));
            assertThat(text(pdf(hr, id))).contains("Lena Changed");
            assertThat(text(before)).contains("Lena Lock");
            mockMvc.perform(get("/api/offer-letters").header("Authorization", auth(hr)))
                    .andExpect(jsonPath("$.rows[?(@.candidateName == 'Lena Changed')].changedByName").value(org.hamcrest.Matchers.hasItem("Hema HR")));
            // the other tick is its own
            mockMvc.perform(delete("/api/offer-letters/" + id).header("Authorization", auth(hr))).andExpect(status().isForbidden());
            menuAccess.save(AccessSubject.EMPLOYEE, hrId, List.of("4004", "4006"));
            mockMvc.perform(delete("/api/offer-letters/" + id).header("Authorization", auth(hr))).andExpect(status().isNoContent());
            mockMvc.perform(get("/api/offer-letters/" + id).header("Authorization", auth(hr))).andExpect(status().isNotFound());
        } finally {
            menuAccess.save(AccessSubject.EMPLOYEE, hrId, List.of());
        }
    }

    @Test
    void aDraftCanBeDeletedAndItsPdfIsMarked() throws Exception {
        String id = idOf(json(post("/api/offer-letters"), hr, letter("Dev Draft", "2026-11-04", "2026-11-16", "600000", SPLIT)).andExpect(status().isCreated()));
        assertThat(text(pdf(hr, id))).contains("Draft, not issued", "Dev Draft");
        mockMvc.perform(delete("/api/offer-letters/" + id).header("Authorization", auth(hr))).andExpect(status().isNoContent());
    }

    @Test
    void theIssuedPdfSaysItAllAndStaysAsIssued() throws Exception {
        String id = issued("Fay Frozen");
        String text = text(pdf(hr, id));
        assertThat(text).contains("OFFER OF EMPLOYMENT", "Fay Frozen", "Software Engineer", "Amsidel Doors & Windows", "Kakkanad, Kochi",
                "16 November 2026", "48 hours a week", "6,00,000", "50,000", "Basic", "Special allowance", "9847012345", "hr@amsidel.com",
                "Report on your joining date", "Hema HR", "HR Manager", "DW/OL/2026/");
        // a later change to the brand does not touch a letter already issued
        Brand b = brands.findById(dw).orElseThrow();
        String name = b.getName();
        b.setName("Renamed Brand");
        brands.save(b);
        try {
            assertThat(pdf(hr, id)).isEqualTo(pdf(hr, id));
            assertThat(text(pdf(hr, id))).contains("Amsidel Doors & Windows").doesNotContain("Renamed Brand");
        } finally {
            b.setName(name);
            brands.save(b);
        }
    }

    @Test
    void eachBrandSeesItsOwn() throws Exception {
        String id = issued("Bea Brand");
        mockMvc.perform(get("/api/offer-letters/" + id).header("Authorization", auth(otherAdmin))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/offer-letters/" + id + "/pdf").header("Authorization", auth(otherAdmin))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/offer-letters").header("Authorization", auth(otherAdmin)))
                .andExpect(jsonPath("$.rows[?(@.candidateName == 'Bea Brand')]").isEmpty());
        json(post("/api/offer-letters"), otherAdmin, "{\"brandId\":%d,\"candidateName\":\"Not Mine\"}".formatted(dw)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/offer-letters/defaults").param("brandId", String.valueOf(dw)).header("Authorization", auth(otherAdmin))).andExpect(status().isForbidden());
    }

    @Test
    void aNewLetterStartsFromTheBrandAndItsLastLetter() throws Exception {
        mockMvc.perform(get("/api/offer-letters/defaults").param("brandId", String.valueOf(other)).header("Authorization", auth(otherAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("Other Brand"))
                .andExpect(jsonPath("$.weeklyHours").value(54))
                .andExpect(jsonPath("$.issuedByName").value("Olga Admin"))
                .andExpect(jsonPath("$.companyIntro", containsString("Other Brand")))
                .andExpect(jsonPath("$.clauses", containsString("Report on your joining date")));
        issued("Ida Intro");
        mockMvc.perform(get("/api/offer-letters/defaults").param("brandId", String.valueOf(dw)).header("Authorization", auth(hr)))
                .andExpect(jsonPath("$.companyIntro").value("We make doors and windows."));
    }

    private static final String SPLIT = "[{\"name\":\"Basic\",\"annualAmount\":300000},{\"name\":\"HRA\",\"annualAmount\":120000},{\"name\":\"Special allowance\",\"annualAmount\":180000}]";

    private String issued(String candidate) throws Exception {
        String id = idOf(json(post("/api/offer-letters"), hr, letter(candidate, "2026-11-04", "2026-11-16", "600000", SPLIT)).andExpect(status().isCreated()));
        json(post("/api/offer-letters/" + id + "/issue"), hr, "{}").andExpect(status().isOk());
        return id;
    }

    private String letter(String candidate, String issueDate, String joining, String annual, String lines) {
        return """
                {"brandId":%d,"issueDate":"%s","companyName":"Amsidel Doors & Windows","companyAddress":"Kakkanad, Kochi, Kerala",
                 "companyIntro":"We make doors and windows.","candidateName":"%s","candidateAddress":"12 MG Road, Kochi","designation":"Software Engineer",
                 "joiningDate":"%s","startDate":"%s","weeklyHours":48,"annualPackage":%s,"monthlySalary":50000,"lines":%s,
                 "contactNumber":"9847012345","contactEmail":"hr@amsidel.com","clauses":"Report on your joining date.\\nKeep information confidential.",
                 "issuedByName":"Hema HR","issuedByDesignation":"HR Manager"}
                """.formatted(dw, issueDate, candidate, joining, joining, annual, lines);
    }

    private byte[] pdf(String token, String id) throws Exception {
        byte[] file = mockMvc.perform(get("/api/offer-letters/" + id + "/pdf").header("Authorization", auth(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(file, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        return file;
    }

    /** All the pages' text, on one line. */
    private static String text(byte[] pdf) throws Exception {
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder all = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(extractor.getTextFromPage(page)).append(' ');
            }
            return all.toString().replaceAll("\\s+", " ");
        }
    }

    private String person(String name, String userId, String role, long brandId, Integer designationId) throws Exception {
        String designation = designationId == null ? "" : "\"designationId\":" + designationId + ",";
        return idOf(json(post("/api/employees"), owner, """
                {"name":"%s","userId":"%s","password":"ab@123","role":"%s","brandId":%d,%s"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(name, userId, role, brandId, designation)).andExpect(status().isCreated()));
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
