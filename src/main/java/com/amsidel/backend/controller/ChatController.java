/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.ChatDtos.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Team chat, for people with the Team chat checkpoint; the chat window polls /sync. */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Checkpoint({Menus.CHAT})
public class ChatController {

    private final ChatService chat;

    /** My chats, messages newer than {@code after} (-1 = none, the first look), who is typing and who is online. */
    @GetMapping("/sync")
    public Sync sync(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "-1") long after) {
        return chat.sync(me, after);
    }

    @GetMapping("/people")
    public List<Person> people(@AuthenticationPrincipal AuthUser me) {
        return chat.people(me);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageView> messages(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                      @RequestParam(required = false) Long before, @RequestParam(defaultValue = "40") int size) {
        return chat.messages(me, id, before, size);
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody SendBody body) {
        return chat.send(me, id, body);
    }

    @PostMapping("/conversations/{id}/read/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @PathVariable long messageId) {
        chat.read(me, id, messageId);
    }

    @PostMapping("/conversations/{id}/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void typing(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @RequestParam(defaultValue = "true") boolean on) {
        chat.typing(me, id, on);
    }

    @PostMapping("/conversations/direct")
    public ConversationView direct(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody DirectBody body) {
        return chat.direct(me, body.getEmployeeId());
    }

    @PostMapping("/conversations/group")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView group(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody GroupBody body) {
        return chat.group(me, body);
    }

    @PostMapping("/conversations/{id}/members")
    public ConversationView addMembers(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody MembersBody body) {
        return chat.addMembers(me, id, body);
    }

    @DeleteMapping("/conversations/{id}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        chat.leave(me, id);
    }
}
