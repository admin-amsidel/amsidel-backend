/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.scheduler.ChatCleanup;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Team chat: only people with the Team chat checkpoint chat and are offered; one-to-one chats (once per pair) and
 * groups; unread counts, read ticks, typing; nobody reads a chat they are not in; the daily clean-up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=chat", "app.chat.retention-days=7"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatCleanup cleanup;

    private String owner;
    private long dw;
    private String anuId;
    private String raviId;
    private String noChatId;
    private String anu;
    private String ravi;
    private String noChat;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = brandIds.getFirst();
        anuId = person("Anu Thomas", "ch.anu", true);
        raviId = person("Ravi Kumar", "ch.ravi", true);
        noChatId = person("No Chat", "ch.none", false);
        anu = token(changePin(token(login("ch.anu", "ab@123")), "ab@123", "cd@456"));
        ravi = token(changePin(token(login("ch.ravi", "ab@123")), "ab@123", "cd@456"));
        noChat = token(changePin(token(login("ch.none", "ab@123")), "ab@123", "cd@456"));
    }

    @Test
    void teamChatBetweenPeopleWithTheCheckpoint() throws Exception {
        // without the checkpoint: no chat, and not offered to others
        mockMvc.perform(get("/api/chat/sync").header("Authorization", auth(noChat))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/chat/people").header("Authorization", auth(anu)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems("Ravi Kumar", "Amsidel Owner")))
                .andExpect(jsonPath("$[*].name", not(hasItem("No Chat"))))
                .andExpect(jsonPath("$[*].name", not(hasItem("Anu Thomas"))));
        json(post("/api/chat/conversations/direct"), anu, "{\"employeeId\":%s}".formatted(noChatId)).andExpect(status().isBadRequest());

        // one-to-one: made once per pair, from either side
        String direct = idOf(json(post("/api/chat/conversations/direct"), anu, "{\"employeeId\":%s}".formatted(raviId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("DIRECT")).andExpect(jsonPath("$.title").value("Ravi Kumar")));
        json(post("/api/chat/conversations/direct"), ravi, "{\"employeeId\":%s}".formatted(anuId))
                .andExpect(jsonPath("$.id").value(Integer.parseInt(direct))).andExpect(jsonPath("$.title").value("Anu Thomas"));

        String first = idOf(json(post("/api/chat/conversations/" + direct + "/messages"), anu, "{\"content\":\"  Site visit at 4 today?  \"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.content").value("Site visit at 4 today?")));
        json(post("/api/chat/conversations/" + direct + "/messages"), anu, "{\"content\":\"   \"}").andExpect(status().isBadRequest());

        // Ravi: one unread, the message comes with the next poll; Anu sees it not yet read
        mockMvc.perform(get("/api/chat/sync").header("Authorization", auth(ravi)))
                .andExpect(jsonPath("$.unreadTotal").value(1))
                .andExpect(jsonPath("$.retentionDays").value(7))
                .andExpect(jsonPath("$.conversations[0].unread").value(1))
                .andExpect(jsonPath("$.conversations[0].last.content").value("Site visit at 4 today?"));
        mockMvc.perform(get("/api/chat/sync").param("after", String.valueOf(Long.parseLong(first) - 1)).header("Authorization", auth(ravi)))
                .andExpect(jsonPath("$.messages[0].senderName").value("Anu Thomas"));
        mockMvc.perform(get("/api/chat/sync").header("Authorization", auth(anu)))
                .andExpect(jsonPath("$.conversations[0].othersRead").value(0));

        // typing shows to the other side; reading clears the unread and gives Anu the tick
        mockMvc.perform(post("/api/chat/conversations/" + direct + "/typing").header("Authorization", auth(ravi))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/chat/sync").header("Authorization", auth(anu)))
                .andExpect(jsonPath("$.typing['" + direct + "'][0]").value("Ravi Kumar"))
                .andExpect(jsonPath("$.online", hasItem(Integer.parseInt(raviId))));
        mockMvc.perform(post("/api/chat/conversations/" + direct + "/read/" + first).header("Authorization", auth(ravi))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/chat/sync").header("Authorization", auth(ravi))).andExpect(jsonPath("$.unreadTotal").value(0));
        mockMvc.perform(get("/api/chat/sync").header("Authorization", auth(anu)))
                .andExpect(jsonPath("$.conversations[0].othersRead").value(Integer.parseInt(first)));

        // a group with the owner; whoever is in it adds people; the last messages page oldest first
        String group = idOf(json(post("/api/chat/conversations/group"), anu, "{\"name\":\"Kochi site team\",\"memberIds\":[%s]}".formatted(raviId))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.members.length()").value(2)));
        json(post("/api/chat/conversations/" + group + "/members"), ravi, "{\"memberIds\":[%s]}".formatted(ownerId()))
                .andExpect(jsonPath("$.members.length()").value(3));
        json(post("/api/chat/conversations/" + group + "/members"), ravi, "{\"memberIds\":[%s]}".formatted(noChatId)).andExpect(status().isBadRequest());
        json(post("/api/chat/conversations/" + group + "/messages"), owner, "{\"content\":\"Good morning team\"}").andExpect(status().isCreated());
        json(post("/api/chat/conversations/" + group + "/messages"), ravi, "{\"content\":\"Morning!\"}").andExpect(status().isCreated());
        mockMvc.perform(get("/api/chat/conversations/" + group + "/messages").header("Authorization", auth(anu)))
                .andExpect(jsonPath("$[0].content").value("Good morning team")).andExpect(jsonPath("$[1].content").value("Morning!"));
        mockMvc.perform(get("/api/chat/sync").header("Authorization", auth(anu)))
                .andExpect(jsonPath("$.conversations[0].title").value("Kochi site team"))
                .andExpect(jsonPath("$.conversations[0].unread").value(2));

        // not in a chat: not found
        mockMvc.perform(get("/api/chat/conversations/" + direct + "/messages").header("Authorization", auth(owner))).andExpect(status().isNotFound());
        json(post("/api/chat/conversations/" + direct + "/messages"), owner, "{\"content\":\"hello\"}").andExpect(status().isNotFound());

        // the owner leaves the group
        mockMvc.perform(delete("/api/chat/conversations/" + group + "/members/me").header("Authorization", auth(owner))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/chat/sync").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.conversations[?(@.id == %s)]".formatted(group)).isEmpty());

        // the daily clean-up: messages before the limit go; the emptied one-to-one goes, the group stays
        int[] done = cleanup.clearBefore(Instant.now().plusSeconds(5));
        assertThat(done[0]).isEqualTo(3);
        mockMvc.perform(get("/api/chat/sync").header("Authorization", auth(anu)))
                .andExpect(jsonPath("$.conversations.length()").value(1))
                .andExpect(jsonPath("$.conversations[0].title").value("Kochi site team"))
                .andExpect(jsonPath("$.conversations[0].last").doesNotExist())
                .andExpect(jsonPath("$.unreadTotal").value(0));
    }

    // ---- helpers ----

    private String ownerId() throws Exception {
        return String.valueOf((Object) JsonPath.read(body(mockMvc.perform(get("/api/auth/me").header("Authorization", auth(owner)))), "$.id"));
    }

    /** A DW employee; withChat = given the Team chat checkpoint (12000). */
    private String person(String name, String userId, boolean withChat) throws Exception {
        String id = idOf(json(post("/api/employees"), owner,
                "{\"name\":\"%s\",\"userId\":\"%s\",\"password\":\"ab@123\",\"role\":\"EMPLOYEE\",\"brandId\":%d,\"phone\":\"+91 98470 00000\",\"joinedDate\":\"2026-01-05\"}"
                        .formatted(name, userId, dw)).andExpect(status().isCreated()));
        json(put("/api/menu-access/EMPLOYEE/" + id), owner, withChat ? "{\"checkpoints\":[\"12000\",\"1001\"]}" : "{\"checkpoints\":[\"1001\"]}")
                .andExpect(status().isOk());
        return id;
    }

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
