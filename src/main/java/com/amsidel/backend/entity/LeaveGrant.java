package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Days added to one person's balance for one year by an admin or manager: comp-off for a holiday worked,
 * paternity leave, or extra days on top of an annual quota.
 */
@Entity
@Table(name = "leave_grants")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class LeaveGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id")
    private LeaveType leaveType;

    /** Calendar year the days can be used in. */
    @Column(nullable = false)
    private int leaveYear;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal days;

    @Column(nullable = false, length = 300)
    private String reason;

    /** For comp-off: the holiday or Sunday that was worked. */
    private LocalDate workedOn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "granted_by")
    private Employee grantedBy;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
