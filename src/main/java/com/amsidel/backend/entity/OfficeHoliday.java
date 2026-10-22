package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A day the office is closed for everyone (Christmas, New Year, Onam...). Nobody applies for it: leave across it
 * does not count it, and the work log expects no hours that day. Unlike a restricted holiday, it is not optional.
 */
@Entity
@Table(name = "office_holidays", indexes = @Index(name = "ix_office_holiday_date", columnList = "holidayDate"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate holidayDate;

    @Column(nullable = false, length = 100)
    private String name;

    /** Null = every brand. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
