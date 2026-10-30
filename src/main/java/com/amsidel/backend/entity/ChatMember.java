/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Someone in a chat, and how far they have read. */
@Entity
@Table(name = "chat_members", uniqueConstraints = @UniqueConstraint(name = "uk_chat_member", columnNames = {"conversation_id", "employee_id"}),
        indexes = @Index(name = "ix_chat_members_employee", columnList = "employee_id"))
@Getter
@Setter
@NoArgsConstructor
public class ChatMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ChatConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** The last message they have seen (0 = none). */
    @Column(nullable = false)
    private long lastReadMessageId;

    @Column(nullable = false)
    private Instant joinedAt;
}
