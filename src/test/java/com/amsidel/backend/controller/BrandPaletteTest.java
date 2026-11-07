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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A brand's colour palette: chosen on the brand, and sent to that brand's people when they sign in. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=brandpalette")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrandPaletteTest {

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
    }

    private int brand(String code) throws Exception {
        return JsonPath.read(json(post("/api/brands"), owner, "{\"code\":\"" + code + "\",\"name\":\"Brand " + code + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions setPalette(int id, String code, String palette) throws Exception {
        String field = palette == null ? "" : ",\"colorPalette\":\"" + palette + "\"";
        return json(put("/api/brands/" + id), owner, "{\"code\":\"" + code + "\",\"name\":\"Brand " + code + "\"" + field + "}");
    }

    private String person(int brand) throws Exception {
        String userId = "pal." + (++seq);
        json(post("/api/employees"), owner, """
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, brand)).andExpect(status().isCreated());
        return token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
    }

    private ResultActions access(String token) throws Exception {
        return mockMvc.perform(get("/api/auth/access").header("Authorization", "asd " + token)).andExpect(status().isOk());
    }

    @Test
    void aBrandsPeopleGetItsPaletteAndEveryoneElseDoesNot() throws Exception {
        int a = brand("PA");
        int b = brand("PB");
        String mine = person(a);
        String theirs = person(b);

        // nothing chosen: the standard look
        access(mine).andExpect(jsonPath("$.palette").doesNotExist());

        setPalette(a, "PA", "EARTH").andExpect(status().isOk()).andExpect(jsonPath("$.colorPalette").value("EARTH"));
        access(mine).andExpect(jsonPath("$.palette").value("EARTH"));
        access(theirs).andExpect(jsonPath("$.palette").doesNotExist());
        // group level stays as it is
        access(owner).andExpect(jsonPath("$.palette").doesNotExist());

        // saving the brand without it keeps it; empty goes back to the standard look
        setPalette(a, "PA", null).andExpect(jsonPath("$.colorPalette").value("EARTH"));
        setPalette(a, "PA", "").andExpect(status().isOk()).andExpect(jsonPath("$.colorPalette").doesNotExist());
        access(mine).andExpect(jsonPath("$.palette").doesNotExist());
    }

    @Test
    void onlyAPaletteThatExistsIsTakenInAnyCase() throws Exception {
        int c = brand("PC");
        setPalette(c, "PC", "NEON").andExpect(status().isBadRequest());
        setPalette(c, "PC", "blush").andExpect(status().isOk()).andExpect(jsonPath("$.colorPalette").value("BLUSH"));
        for (String code : new String[]{"VIVID", "SOFT", "BOLD", "EARTH", "BLUSH"}) {
            setPalette(c, "PC", code).andExpect(status().isOk()).andExpect(jsonPath("$.colorPalette").value(code));
        }
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, String content) throws Exception {
        return mockMvc.perform(request.header("Authorization", "asd " + token).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private ResultActions changePin(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password").header("Authorization", "asd " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private static String token(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.token");
    }
}
