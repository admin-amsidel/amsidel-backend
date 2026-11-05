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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** How many documents a person may upload in a month: 10 unless their record says otherwise. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=document-limit")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentUploadLimitTest {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final String ME = "/api/auth/me/documents/";
    private static final String QUOTA = "/api/auth/me/document-quota";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EmployeeRepository employees;

    private String owner;
    private int brand;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        brand = ids.getFirst();
    }

    /** {id, userId, token}. */
    private String[] person() throws Exception {
        String userId = "lim." + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(employeeJson(userId, null))).andExpect(status().isCreated())), "$.id"));
        return new String[]{id, userId, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    private String employeeJson(String userId, String limit) {
        return "{\"name\":\"%s\",\"userId\":\"%s\",\"password\":\"ab@123\",\"role\":\"EMPLOYEE\",\"brandId\":%d,\"phone\":\"9847000000\",\"joinedDate\":\"2026-01-05\",\"documentUploadLimit\":%s}"
                .formatted(userId, userId, brand, limit);
    }

    private ResultActions setLimit(String[] p, String limit) throws Exception {
        return mockMvc.perform(put("/api/employees/" + p[0]).header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content(employeeJson(p[1], limit)));
    }

    private void change(String userId, Consumer<Employee> what) {
        Employee e = employees.findByUserId(userId).orElseThrow();
        what.accept(e);
        employees.save(e);
    }

    @Test
    void tenAMonthByDefaultThenRefused() throws Exception {
        String[] p = person();
        mockMvc.perform(get(QUOTA).header("Authorization", auth(p[2])))
                .andExpect(jsonPath("$.limit").value(10)).andExpect(jsonPath("$.used").value(0)).andExpect(jsonPath("$.remaining").value(10));
        for (int i = 0; i < 10; i++) {
            upload(ME + "PAN_CARD", p[2], PDF).andExpect(status().isOk());
        }
        mockMvc.perform(get(QUOTA).header("Authorization", auth(p[2])))
                .andExpect(jsonPath("$.used").value(10)).andExpect(jsonPath("$.remaining").value(0));
        upload(ME + "PAN_CARD", p[2], PDF).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", containsString("all 10 document uploads")));
        // the document already there is untouched, and can still be opened
        mockMvc.perform(get(ME + "PAN_CARD").header("Authorization", auth(p[2]))).andExpect(status().isOk());
    }

    @Test
    void theLimitIsSetPerPersonAndEmptyMeansTheDefault() throws Exception {
        String[] p = person();
        setLimit(p, "2").andExpect(status().isOk()).andExpect(jsonPath("$.documentUploadLimit").value(2));
        upload(ME + "PAN_CARD", p[2], PDF).andExpect(status().isOk());
        upload(ME + "AADHAAR_CARD", p[2], PDF).andExpect(status().isOk());
        upload(ME + "OFFER_LETTER", p[2], PDF).andExpect(status().isTooManyRequests());

        // raised: they can go on at once, with what they used still counted
        setLimit(p, "3").andExpect(status().isOk());
        mockMvc.perform(get(QUOTA).header("Authorization", auth(p[2]))).andExpect(jsonPath("$.used").value(2)).andExpect(jsonPath("$.remaining").value(1));
        upload(ME + "OFFER_LETTER", p[2], PDF).andExpect(status().isOk());

        // zero: none at all; emptied: the default again
        setLimit(p, "0").andExpect(status().isOk());
        upload(ME + "BANK_PROOF", p[2], PDF).andExpect(status().isTooManyRequests());
        setLimit(p, "null").andExpect(status().isOk()).andExpect(jsonPath("$.documentUploadLimit").isEmpty());
        mockMvc.perform(get(QUOTA).header("Authorization", auth(p[2]))).andExpect(jsonPath("$.limit").value(10));

        setLimit(p, "-1").andExpect(status().isBadRequest());
        setLimit(p, "100000").andExpect(status().isBadRequest());
    }

    @Test
    void replacingCountsDeletingGivesNothingBackAndRefusedFilesDoNotCount() throws Exception {
        String[] p = person();
        setLimit(p, "3").andExpect(status().isOk());
        upload(ME + "PAN_CARD", p[2], PDF).andExpect(status().isOk());
        upload(ME + "PAN_CARD", p[2], PDF).andExpect(status().isOk());
        mockMvc.perform(delete(ME + "PAN_CARD").header("Authorization", auth(p[2]))).andExpect(status().isNoContent());
        mockMvc.perform(get(QUOTA).header("Authorization", auth(p[2]))).andExpect(jsonPath("$.used").value(2));

        // too big, or not a document: refused, and not counted
        upload(ME + "PAN_CARD", p[2], new byte[2 * 1024 * 1024 + 1]).andExpect(status().isBadRequest());
        upload(ME + "PAN_CARD", p[2], "MZ not a document".getBytes(StandardCharsets.US_ASCII)).andExpect(status().isBadRequest());
        mockMvc.perform(get(QUOTA).header("Authorization", auth(p[2]))).andExpect(jsonPath("$.used").value(2));
    }

    @Test
    void exactlyTwoMbIsAllowed() throws Exception {
        String[] p = person();
        byte[] twoMb = new byte[2 * 1024 * 1024];
        System.arraycopy(PDF, 0, twoMb, 0, PDF.length);
        upload(ME + "OFFER_LETTER", p[2], twoMb).andExpect(status().isOk());
    }

    @Test
    void aNewMonthStartsAgain() throws Exception {
        String[] p = person();
        setLimit(p, "1").andExpect(status().isOk());
        upload(ME + "PAN_CARD", p[2], PDF).andExpect(status().isOk());
        upload(ME + "PAN_CARD", p[2], PDF).andExpect(status().isTooManyRequests());

        change(p[1], e -> e.setDocumentUploadsMonth("2020-01"));
        mockMvc.perform(get(QUOTA).header("Authorization", auth(p[2]))).andExpect(jsonPath("$.used").value(0)).andExpect(jsonPath("$.remaining").value(1));
        upload(ME + "PAN_CARD", p[2], PDF).andExpect(status().isOk());
        mockMvc.perform(get(QUOTA).header("Authorization", auth(p[2]))).andExpect(jsonPath("$.used").value(1));
        upload(ME + "PAN_CARD", p[2], PDF).andExpect(status().isTooManyRequests());
    }

    @Test
    void whatAnAdminUploadsCountsForTheEmployeeTheFileIsFor() throws Exception {
        String[] p = person();
        setLimit(p, "1").andExpect(status().isOk());
        String url = "/api/employees/" + p[0] + "/documents/OFFER_LETTER";
        upload(url, owner, PDF).andExpect(status().isOk());
        mockMvc.perform(get("/api/employees/" + p[0] + "/document-quota").header("Authorization", auth(owner))).andExpect(jsonPath("$.used").value(1));
        upload(ME + "PAN_CARD", p[2], PDF).andExpect(status().isTooManyRequests());
        upload(url, owner, PDF).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error", containsString(p[1])));
        // someone else's count is not for everyone to read
        String[] other = person();
        mockMvc.perform(get("/api/employees/" + p[0] + "/document-quota").header("Authorization", auth(other[2]))).andExpect(status().isForbidden());
    }

    @Test
    void uploadsTogetherCannotGoPastTheLimit() throws Exception {
        String[] p = person();
        setLimit(p, "2").andExpect(status().isOk());
        List<Integer> answers = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (String type : List.of("PAN_CARD", "AADHAAR_CARD", "BANK_PROOF", "OFFER_LETTER")) {
            Thread t = new Thread(() -> {
                try {
                    answers.add(upload(ME + type, p[2], PDF).andReturn().getResponse().getStatus());
                } catch (Exception e) {
                    answers.add(-1);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(30000);
        }
        assertThat(answers).hasSize(4);
        assertThat(answers.stream().filter(s -> s == 200)).hasSize(2);
        mockMvc.perform(get(QUOTA).header("Authorization", auth(p[2]))).andExpect(jsonPath("$.used").value(2));
    }

    private ResultActions upload(String url, String token, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart(url).file(new MockMultipartFile("file", "doc.pdf", "application/octet-stream", bytes)).header("Authorization", auth(token)));
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
