/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** A meeting on the dashboard's calendar: seen by the person who added it and the people in it. */
@Entity
@Table(name = "meetings", indexes = @Index(name = "idx_meetings_date", columnList = "meeting_date"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "meeting_date", nullable = false)
    private LocalDate meetingDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    /** Null = no end time given. */
    @Column(name = "end_time")
    private LocalTime endTime;

    /** A room, an address or a call link. */
    @Column(length = 300)
    private String location;

    @Column(length = 2000)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private Employee createdBy;

    /** Always includes the person who added it. */
    @ManyToMany
    @JoinTable(name = "meeting_people",
            joinColumns = @JoinColumn(name = "meeting_id"),
            inverseJoinColumns = @JoinColumn(name = "employee_id"))
    private Set<Employee> people = new LinkedHashSet<>();

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
