package com.amsidel.backend.scheduler;

import com.amsidel.backend.repository.ChatConversationRepository;
import com.amsidel.backend.repository.ChatMemberRepository;
import com.amsidel.backend.repository.ChatMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final int retentionDays;

    public ChatCleanup(ChatMessageRepository messages, ChatMemberRepository members, ChatConversationRepository conversations,
                       @Value("${app.chat.retention-days:7}") int retentionDays) {
        this.messages = messages;
        this.members = members;
        this.conversations = conversations;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(cron = "${app.chat.clean-cron:0 0 7 * * *}", zone = "${app.leave.zone:Asia/Kolkata}")
    @Transactional
    public void clean() {
        int[] done = clearBefore(Instant.now().minus(Duration.ofDays(retentionDays)));
        log.info("Team chat clean-up: {} message(s) older than {} days cleared, {} empty one-to-one chat(s) removed", done[0], retentionDays, done[1]);
    }

    /** Clears messages before this moment; returns {messages, chats} removed. */
    @Transactional
    public int[] clearBefore(Instant before) {
        int gone = messages.deleteOlderThan(before);
        members.deleteOfEmptyDirect(before);
        int chats = conversations.deleteEmptyDirect(before);
        return new int[] {gone, chats};
    }
}
