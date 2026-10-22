package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Job title and its place in the group's hierarchy, e.g. level 4 = Team Lead. Peers share a level number and
 * differ by levelCode (6.1 Branch Manager, 6.2 Finance Manager). Separate from Role, which is portal access.
 */
@Entity
@Table(name = "designations")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Designation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Rank in the hierarchy, 1 = Trainee ... 10 = CEO; higher is more senior. */
    @Column(nullable = false)
    private int levelNumber;

    /** Shown level, e.g. "4" or "6.1"; unique. */
    @Column(nullable = false, unique = true, length = 10)
    private String levelCode;

    @Column(nullable = false)
    private boolean active = true;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
