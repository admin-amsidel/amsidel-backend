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

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Image and document sizes come from the properties: changed here, they change what is refused and what is said. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "test.context=upload-limits",
        "app.uploads.max-size.profile-photo=1KB",
        "app.uploads.max-size.agent-photo=2KB",
        "app.uploads.max-size.brand-logo=3KB",
        "app.uploads.max-size.check-in-photo=6KB",
        "app.uploads.max-size.upi-qr=4KB",
        "app.uploads.max-size.news-picture=5KB",
        "app.uploads.max-size.document=1500KB"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadLimitsTest {

    private static final byte[] JPEG_START = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private int brand;

    @BeforeAll
    void setUp() throws Exception {
        String first = JsonPath.read(body(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"owner\",\"password\":\"Ams@2026\"}"))), "$.token");
        owner = JsonPath.read(body(mockMvc.perform(post("/api/auth/change-password").header("Authorization", "asd " + first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Ams@2026\",\"newPassword\":\"Own#2026\"}"))), "$.token");
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", "asd " + owner))), "$[?(@.code == 'DW')].id");
        brand = ids.getFirst();
    }

    @Test
    void theWebAppGetsTheFiguresFromTheProperties() throws Exception {
        mockMvc.perform(get("/api/auth/upload-limits").header("Authorization", "asd " + owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.profilePhoto").value(1024))
                .andExpect(jsonPath("$.document").value(1500 * 1024))
                // not changed here: what application.properties says
                .andExpect(jsonPath("$.projectPhoto").value(5 * 1024 * 1024));
        mockMvc.perform(get("/api/auth/upload-limits")).andExpect(status().isUnauthorized());
    }

    @Test
    void eachKindIsRefusedOverItsOwnLimit() throws Exception {
        refused("/api/auth/me/photo", 2 * 1024, "Photo must be 1 KB or smaller");
        refused("/api/brands/" + brand + "/logo", 4 * 1024, "The logo must be 3 KB or smaller");
        refused("/api/brands/" + brand + "/upi-qr", 5 * 1024, "The QR code picture must be 4 KB or smaller");
        refused("/api/auth/me/documents/PASSPORT_PHOTO", 1501 * 1024, "Each file must be 1.5 MB or smaller");
        mockMvc.perform(multipart("/api/attendance/check-in").file(new MockMultipartFile("photo", "me.jpg", "image/jpeg", new byte[7 * 1024]))
                        .param("workMode", "WFH").param("platform", "web").header("Authorization", "asd " + owner))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("The photo must be 6 KB or smaller"));
        String agent = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/agents").header("Authorization", "asd " + owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"Size Agent\",\"phone\":\"9847011111\"}".formatted(brand))).andExpect(status().isCreated())), "$.id"));
        refused("/api/agents/" + agent + "/photo", 3 * 1024, "The photo must be 2 KB or smaller");
    }

    @Test
    void aFileWithinTheLimitIsTaken() throws Exception {
        upload("/api/auth/me/documents/PASSPORT_PHOTO", 1400 * 1024).andExpect(status().isOk());
    }

    private void refused(String url, int size, String message) throws Exception {
        upload(url, size).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(message));
    }

    private ResultActions upload(String url, int size) throws Exception {
        byte[] bytes = new byte[size];
        System.arraycopy(JPEG_START, 0, bytes, 0, JPEG_START.length);
        return mockMvc.perform(multipart(url).file(new MockMultipartFile("file", "picture.jpg", "image/jpeg", bytes)).header("Authorization", "asd " + owner));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
