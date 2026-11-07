/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.scheduler;

import com.amsidel.backend.repository.ChatConversationRepository;
import com.amsidel.backend.repository.ChatMemberRepository;
import com.amsidel.backend.repository.ChatMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Clears team chat messages older than app.chat.retention-days, every day at app.chat.clean-cron (7:00 in the office
 * time zone). One-to-one chats left empty go too; groups stay (empty) until their people leave.
 */
@Slf4j
@Component
public class ChatCleanup {

    private final ChatMessageRepository messages;
    private final ChatMemberRepository members;
    private final ChatConversationRepository conversations;
    private final com.amsidel.backend.service.ChatSignals signals;
    private final int retentionDays;
    private final com.amsidel.backend.service.SchedulerSwitch schedulers;

    public ChatCleanup(ChatMessageRepository messages, ChatMemberRepository members, ChatConversationRepository conversations,
                       com.amsidel.backend.service.ChatSignals signals, com.amsidel.backend.service.SchedulerSwitch schedulers,
                       @Value("${app.chat.retention-days:7}") int retentionDays) {
        this.messages = messages;
        this.members = members;
        this.conversations = conversations;
        this.signals = signals;
        this.schedulers = schedulers;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Transactional
    public void clean() {
        if (!schedulers.allowed(com.amsidel.backend.service.SchedulerSwitch.Job.CHAT_CLEANUP)) {
            return;
        }
        int[] done = clearBefore(Instant.now().minus(Duration.ofDays(retentionDays)));
        log.info("Team chat clean-up: {} message(s) older than {} days cleared, {} empty one-to-one chat(s) removed", done[0], retentionDays, done[1]);
    }

    /** Clears messages before this moment; returns {messages, chats} removed. */
    @Transactional
    public int[] clearBefore(Instant before) {
        int gone = messages.deleteOlderThan(before);
        members.deleteOfEmptyDirect(before);
        int chats = conversations.deleteEmptyDirect(before);
        if (gone + chats > 0) {
            signals.changedForAll();
        }
        return new int[] {gone, chats};
    }
}
