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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Employee documents: everyone uploads, opens, replaces and deletes their own; owners and admins see and change
 * theirs, nobody else; PDF, JPEG or PNG only (the passport size photo an image), 2 MB each; the profile completion
 * counts them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=documents")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmployeeDocumentTest {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private String anu;
    private String anuId;
    private String ravi;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        anuId = employee("Anu Docs", "doc.anu", brandIds.getFirst());
        employee("Ravi Docs", "doc.ravi", brandIds.getFirst());
        anu = token(changePin(token(login("doc.anu", "ab@123")), "ab@123", "cd@456"));
        ravi = token(changePin(token(login("doc.ravi", "ab@123")), "ab@123", "cd@456"));
    }

    @Test
    void myOwnDocumentsAndTheProfileCompletion() throws Exception {
        mockMvc.perform(get("/api/auth/me/documents").header("Authorization", auth(anu)))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[*].uploaded", everyItem(is(false))));
        int before = JsonPath.read(body(mockMvc.perform(get("/api/auth/me/profile-status").header("Authorization", auth(anu)))), "$.percent");

        upload("/api/auth/me/documents/PAN_CARD", anu, file("C:\\scans\\my pan.pdf", PDF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploaded").value(true))
                .andExpect(jsonPath("$.fileName").value("my pan.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.uploadedByName").value("Anu Docs"));
        mockMvc.perform(get("/api/auth/me/documents/PAN_CARD").header("Authorization", auth(anu)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().bytes(PDF));
        mockMvc.perform(get("/api/auth/me/profile-status").header("Authorization", auth(anu)))
                .andExpect(jsonPath("$.percent").value(greaterThan(before)))
                .andExpect(jsonPath("$.items[?(@.key == 'doc:PAN_CARD')].done", contains(true)))
                .andExpect(jsonPath("$.items[?(@.key == 'doc:AADHAAR_CARD')].done", contains(false)));

        // replacing keeps one per type; deleting removes it
        upload("/api/auth/me/documents/PAN_CARD", anu, file("pan.png", PNG)).andExpect(jsonPath("$.contentType").value("image/png"));
        mockMvc.perform(get("/api/auth/me/documents").header("Authorization", auth(anu)))
                .andExpect(jsonPath("$[?(@.uploaded == true)].type", contains("PAN_CARD")));
        mockMvc.perform(delete("/api/auth/me/documents/PAN_CARD").header("Authorization", auth(anu))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me/documents/PAN_CARD").header("Authorization", auth(anu))).andExpect(status().isNotFound());
    }

    @Test
    void onlyPdfOrImagesUpTo2Mb() throws Exception {
        upload("/api/auth/me/documents/AADHAAR_CARD", ravi, file("aadhaar.exe", "MZ not a document".getBytes(StandardCharsets.US_ASCII)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("PDF, JPEG or PNG")));
        upload("/api/auth/me/documents/PASSPORT_PHOTO", ravi, file("photo.pdf", PDF))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("JPEG or PNG image")));
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        upload("/api/auth/me/documents/OFFER_LETTER", ravi, file("offer.pdf", big))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("2 MB")));
    }

    @Test
    void ownersSeeThemOthersDoNot() throws Exception {
        upload("/api/auth/me/documents/BANK_PROOF", anu, file("passbook.pdf", PDF)).andExpect(status().isOk());
        mockMvc.perform(get("/api/employees/" + anuId + "/documents").header("Authorization", auth(ravi))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/employees/" + anuId + "/documents/BANK_PROOF").header("Authorization", auth(ravi))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/employees/" + anuId + "/documents").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.type == 'BANK_PROOF')].uploaded", contains(true)));
        mockMvc.perform(get("/api/employees/" + anuId + "/documents/BANK_PROOF").header("Authorization", auth(owner)))
                .andExpect(content().bytes(PDF));
        upload("/api/employees/" + anuId + "/documents/OFFER_LETTER", owner, file("offer.pdf", PDF))
                .andExpect(jsonPath("$.uploadedByName", not("Anu Docs")));
        mockMvc.perform(get("/api/employees/" + anuId + "/profile-status").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.items[?(@.key == 'doc:OFFER_LETTER')].done", contains(true)));
    }

    @Test
    void myOwnPersonalDetailsOnly() throws Exception {
        int before = JsonPath.read(body(mockMvc.perform(get("/api/auth/me/profile-status").header("Authorization", auth(ravi)))), "$.percent");
        mockMvc.perform(put("/api/auth/me/details").header("Authorization", auth(ravi)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"+91 98470 11122","personalEmail":"Ravi@Example.com","emergencyContact":"9847012345",
                                 "address":" 12 MG Road, Kochi ","dob":"1994-03-15","bloodGroup":"O+",
                                 "name":"Not Changed","companyEmail":"x@amsidel.com","role":"OWNER"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalEmail").value("ravi@example.com"))
                .andExpect(jsonPath("$.address").value("12 MG Road, Kochi"))
                .andExpect(jsonPath("$.bloodGroup").value("O+"))
                .andExpect(jsonPath("$.dob").value("1994-03-15"))
                .andExpect(jsonPath("$.name").value("Ravi Docs"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.companyEmail").doesNotExist());
        mockMvc.perform(get("/api/auth/me/profile-status").header("Authorization", auth(ravi)))
                .andExpect(jsonPath("$.percent").value(greaterThan(before)));
        // checked like the admin's form
        mockMvc.perform(put("/api/auth/me/details").header("Authorization", auth(ravi)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"\",\"bloodGroup\":\"Z\",\"dob\":\"2999-01-01\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- helpers ----

    private String employee(String name, String userId, int brandId) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"userId\":\"%s\",\"password\":\"ab@123\",\"role\":\"EMPLOYEE\",\"brandId\":%d,\"phone\":\"98470%05d\",\"joinedDate\":\"2026-01-05\"}"
                        .formatted(name, userId, brandId, Math.abs(userId.hashCode()) % 100000)))
                .andExpect(status().isCreated())), "$.id"));
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    private ResultActions upload(String url, String token, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart(url).file(file).header("Authorization", auth(token)));
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

    private static String auth(String token) {
        return "asd " + token;
    }
}
