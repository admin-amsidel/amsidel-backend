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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A designation's short code and description, kept apart from its name. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=designationcode")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignationCodeTest {

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

    private ResultActions send(MockHttpServletRequestBuilder request, String content) throws Exception {
        return mockMvc.perform(request.header("Authorization", "asd " + owner).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private int brand(String code) throws Exception {
        return JsonPath.read(send(post("/api/brands"), "{\"code\":\"" + code + "\",\"name\":\"Brand " + code + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void aNewDatabaseStartsWithShortCodes() throws Exception {
        mockMvc.perform(get("/api/designations").header("Authorization", "asd " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Team Lead')].shortCode", hasItem("TL")))
                .andExpect(jsonPath("$[?(@.name == 'CEO')].shortCode", hasItem("CEO")));
    }

    @Test
    void theCodeAndDescriptionAreSavedAndChangedOnTheirOwn() throws Exception {
        String created = send(post("/api/designations"), """
                {"name":"Sales Executive","shortCode":"se","levelNumber":50,"levelCode":"50"}
                """).andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");
        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(created, "$.shortCode")).isEqualTo("SE");

        send(put("/api/designations/" + id), """
                {"name":"Sales Executive","shortCode":"SE","description":"  Sells to walk-in customers  ","levelNumber":50,"levelCode":"50"}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sales Executive"))
                .andExpect(jsonPath("$.description").value("Sells to walk-in customers"));

        // not sent: kept
        send(put("/api/designations/" + id), """
                {"name":"Senior Sales Executive","levelNumber":50,"levelCode":"50"}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("SE"))
                .andExpect(jsonPath("$.description").value("Sells to walk-in customers"));

        // empty: taken off
        send(put("/api/designations/" + id), """
                {"name":"Senior Sales Executive","shortCode":"","description":"","levelNumber":50,"levelCode":"50"}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void aShortCodeIsUniqueWithinALadderOnly() throws Exception {
        int a = brand("DCA");
        int b = brand("DCB");
        send(post("/api/designations"), """
                {"name":"Fitter","shortCode":"FIT","levelNumber":3,"levelCode":"3","brandId":%d}
                """.formatted(a)).andExpect(status().is2xxSuccessful());
        // another name, the same code in any case: refused on the same ladder
        send(post("/api/designations"), """
                {"name":"Fitter Two","shortCode":"fit","levelNumber":4,"levelCode":"4","brandId":%d}
                """.formatted(a)).andExpect(status().isConflict());
        // another brand may have it
        send(post("/api/designations"), """
                {"name":"Fitter","shortCode":"FIT","levelNumber":3,"levelCode":"3","brandId":%d}
                """.formatted(b)).andExpect(status().is2xxSuccessful());
    }

    @Test
    void aBadCodeOrLongDescriptionIsRefused() throws Exception {
        send(post("/api/designations"), """
                {"name":"Bad One","shortCode":"T L!","levelNumber":60,"levelCode":"60"}
                """).andExpect(status().isBadRequest());
        send(post("/api/designations"), """
                {"name":"Bad Two","shortCode":"BT","description":"%s","levelNumber":61,"levelCode":"61"}
                """.formatted("x".repeat(501))).andExpect(status().isBadRequest());
    }

    @Test
    void oldStyleSavesWithoutTheNewFieldsStillWork() throws Exception {
        send(post("/api/designations"), """
                {"name":"Plain One","levelNumber":70,"levelCode":"70"}
                """).andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.shortCode").doesNotExist());
        send(post("/api/designations"), """
                {"name":"Plain Two","levelNumber":71,"levelCode":"71"}
                """).andExpect(status().is2xxSuccessful());
    }
}
