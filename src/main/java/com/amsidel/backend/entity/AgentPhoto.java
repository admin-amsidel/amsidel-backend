/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An agent's photo, kept apart from {@link Agent} so listing agents never loads image bytes. */
@Entity
@Table(name = "agent_photos")
@Getter
@Setter
@NoArgsConstructor
public class AgentPhoto {

    /** Same value as agents.id. */
    @Id
    private Long agentId;

    /** bytea spelled out: a large length would otherwise become an "oid" large object on PostgreSQL. */
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(nullable = false, length = 30)
    private String contentType;
}
