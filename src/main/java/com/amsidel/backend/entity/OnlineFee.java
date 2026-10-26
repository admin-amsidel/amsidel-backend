package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** The processing fee added on top of the amount paid online, as a percent, for each way of paying (3% to start). */
@Entity
@Table(name = "online_fees")
@Getter
@Setter
@NoArgsConstructor
public class OnlineFee {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private OnlineMethod method;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percent;

    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Employee updatedBy;
}
