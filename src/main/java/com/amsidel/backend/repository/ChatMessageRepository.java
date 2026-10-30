/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.repository;

import com.amsidel.backend.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** A page of a chat, newest first (before = the oldest id already shown, or null). */
    @Query("select m from ChatMessage m join fetch m.sender where m.conversation.id = :id and (:before is null or m.id < :before) order by m.id desc")
    List<ChatMessage> page(@Param("id") Long conversationId, @Param("before") Long before, Pageable page);

    /** New messages in my chats since the last one I have. */
    @Query("select m from ChatMessage m join fetch m.sender where m.conversation.id in :ids and m.id > :after order by m.id")
    List<ChatMessage> after(@Param("ids") Collection<Long> conversationIds, @Param("after") long after, Pageable page);

    /** The last message of each chat. */
    @Query("select m from ChatMessage m join fetch m.sender where m.id in (select max(x.id) from ChatMessage x where x.conversation.id in :ids group by x.conversation.id)")
    List<ChatMessage> lastOf(@Param("ids") Collection<Long> conversationIds);

    /** Unread per chat: [conversationId, count] of others' messages after what I read. */
    @Query("select m.conversation.id, count(m) from ChatMessage m, ChatMember me where me.employee.id = :employeeId"
            + " and me.conversation = m.conversation and m.id > me.lastReadMessageId and m.sender.id <> :employeeId group by m.conversation.id")
    List<Object[]> unread(@Param("employeeId") Long employeeId);

    @Query("select coalesce(max(m.id), 0) from ChatMessage m where m.conversation.id = :id")
    long lastId(@Param("id") Long conversationId);

    @Modifying
    @Query("delete from ChatMessage m where m.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
