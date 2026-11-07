/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.config.MenuBootstrap;
import com.amsidel.backend.dto.MenuAccessEntry;
import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.repository.MenuItemRepository;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.MenuAccessService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Overview's tick, and its Storage and My sales sections' own ticks. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=overviewaccess")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverviewAccessTest {

    private static final String OVERVIEW = "400";
    private static final String STORAGE_SECTION = "401";
    private static final String SALES_SECTION = "402";
    private static final String MY_WORK_LOG = "3001";
    private static final String STORAGE_PAGE = "9010";
    private static final String MANUAL_LOG = "3006";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MenuAccessService menuAccess;
    @Autowired
    private MenuBootstrap bootstrap;
    @Autowired
    private MenuItemRepository menus;

    private String owner;
    private int dw;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.getFirst();
    }

    /** {id, token}; with no ticks given, the person follows their user type. */
    private String[] person(String... checkpoints) throws Exception {
        String userId = "ov." + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Person %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, dw))).andExpect(status().isCreated())), "$.id"));
        if (checkpoints.length > 0) {
            menuAccess.save(AccessSubject.EMPLOYEE, id, List.of(checkpoints));
        }
        return new String[]{id, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    private ResultActions call(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", auth(token)));
    }

    @Test
    void employeesHaveTheOverviewByDefaultButNotItsSections() throws Exception {
        String p = person()[1];
        call("/api/dashboard/summary", p).andExpect(status().isOk());
        call("/api/auth/access", p).andExpect(jsonPath("$.codes", hasItem("OVERVIEW")))
                .andExpect(jsonPath("$.codes", not(hasItem("OVERVIEW_STORAGE")))).andExpect(jsonPath("$.codes", not(hasItem("OVERVIEW_SALES"))));
        call("/api/storage/summary", p).andExpect(status().isForbidden());
        call("/api/manual-sales/summary", p).andExpect(status().isForbidden());
    }

    @Test
    void withoutTheTickTheOverviewIsClosedEveryPartOfIt() throws Exception {
        String p = person(MY_WORK_LOG)[1];
        for (String part : List.of("summary", "hours", "attendance", "leave", "tasks", "pay", "mail", "profile", "meetings-today", "team/summary", "team/people")) {
            call("/api/dashboard/" + part, p).andExpect(status().isForbidden());
        }
        // their other pages are as they were
        call("/api/tasks/mine", p).andExpect(status().isOk());
        call("/api/auth/access", p).andExpect(jsonPath("$.codes", not(hasItem("OVERVIEW"))));
        // and the owner always has it
        call("/api/dashboard/summary", owner).andExpect(status().isOk());
    }

    @Test
    void eachSectionHasItsOwnTick() throws Exception {
        String storage = person(OVERVIEW, STORAGE_SECTION)[1];
        call("/api/storage/summary", storage).andExpect(status().isOk());
        call("/api/manual-sales/summary", storage).andExpect(status().isForbidden());
        // the section shows the figures, not the files, and not the page's delete
        call("/api/storage/files", storage).andExpect(status().isForbidden());

        String sales = person(OVERVIEW, SALES_SECTION)[1];
        call("/api/manual-sales/summary", sales).andExpect(status().isOk());
        call("/api/storage/summary", sales).andExpect(status().isForbidden());
        // their team's figures still need the details tick
        call("/api/manual-sales/summary?team=true", sales).andExpect(status().isForbidden());
    }

    @Test
    void whenTheTicksFirstAppearNobodyLosesWhatTheyHad() throws Exception {
        String[] plain = person(MY_WORK_LOG);
        String[] storage = person(MY_WORK_LOG, STORAGE_PAGE);
        String[] seller = person(MY_WORK_LOG, MANUAL_LOG);

        // as a database from before these ticks: the menus are not there
        menus.findAll().stream().filter(m -> List.of(Menus.OVERVIEW_STORAGE, Menus.OVERVIEW_SALES, Menus.OVERVIEW).contains(m.getCode())).forEach(menus::delete);
        bootstrap.run(null);

        assertThat(ticks(AccessSubject.EMPLOYEE, plain[0])).contains(OVERVIEW).doesNotContain(STORAGE_SECTION, SALES_SECTION);
        assertThat(ticks(AccessSubject.EMPLOYEE, storage[0])).contains(OVERVIEW, STORAGE_SECTION).doesNotContain(SALES_SECTION);
        assertThat(ticks(AccessSubject.EMPLOYEE, seller[0])).contains(OVERVIEW, SALES_SECTION).doesNotContain(STORAGE_SECTION);
        assertThat(ticks(AccessSubject.ROLE, "EMPLOYEE")).contains(OVERVIEW);
        assertThat(ticks(AccessSubject.ROLE, "MANAGER")).contains(OVERVIEW);

        // taken away afterwards, it stays away at the next start
        menuAccess.save(AccessSubject.EMPLOYEE, plain[0], List.of(MY_WORK_LOG));
        bootstrap.run(null);
        assertThat(ticks(AccessSubject.EMPLOYEE, plain[0])).doesNotContain(OVERVIEW);
    }

    private List<String> ticks(AccessSubject type, String key) {
        return menuAccess.entries().stream().filter(e -> e.subjectType() == type && e.subjectKey().equals(key))
                .findFirst().map(e -> List.copyOf(e.checkpoints())).orElseGet(List::of);
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
