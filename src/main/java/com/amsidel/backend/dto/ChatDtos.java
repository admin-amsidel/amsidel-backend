package com.amsidel.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Team chat (the chat window in the portal). */
public final class ChatDtos {

    private ChatDtos() {
    }

    /** Someone who can chat. */
    public record Person(Long id, String name, String designation, String brandCode, Instant photoUpdatedAt, boolean online) {
    }

    public record MessageView(Long id, Long conversationId, Long senderId, String senderName, String content, Instant createdAt) {
    }

    /**
     * One of my chats: title (the group name, or the other person), its people, the last message, my unread count, and
     * how far the others have read (for the ticks on my messages: one-to-one = the other person, group = everyone).
     */
    public record ConversationView(Long id, String type, String title, Long createdById, List<Person> members, MessageView last,
                                   int unread, long myLastRead, long othersRead, Instant lastMessageAt) {
    }

    /**
     * What the window asks for every few seconds: my chats, messages newer than the last one it has, who is typing
     * (conversation id -> names) and who is online. retentionDays: how long messages are kept.
     */
    public record Sync(List<ConversationView> conversations, List<MessageView> messages, Map<Long, List<String>> typing,
                       List<Long> online, int unreadTotal, int retentionDays, Instant serverTime) {
    }

    @Data
    public static class DirectBody {
        @NotNull
        private Long employeeId;
    }

    @Data
    public static class GroupBody {
        @NotBlank(message = "Give the group a name")
        @Size(max = 120)
        private String name;

        @NotEmpty(message = "Add at least one person")
        @Size(max = 200)
        private List<Long> memberIds;
    }

    @Data
    public static class MembersBody {
        @NotEmpty(message = "Choose who to add")
        @Size(max = 200)
        private List<Long> memberIds;
    }

    @Data
    public static class SendBody {
        @NotBlank(message = "Type a message")
        @Size(max = 4000, message = "A message can be at most 4000 characters")
        private String content;
    }
}
