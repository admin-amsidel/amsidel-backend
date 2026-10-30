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

/** A team chat: one-to-one (DIRECT, once per pair) or a named group. */
@Entity
@Table(name = "chat_conversations")
@Getter
@Setter
@NoArgsConstructor
public class ChatConversation {

    public enum Type { DIRECT, GROUP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    /** Groups only. */
    @Column(length = 120)
    private String name;

    /** "smallerId:largerId" for a one-to-one chat, so each pair has one. */
    @Column(length = 50, unique = true)
    private String directKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private Employee createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastMessageAt;
}
