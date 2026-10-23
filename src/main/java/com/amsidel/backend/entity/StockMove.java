package com.amsidel.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One change to an item's stock, never changed afterwards: quantity is + for stock in, - for stock out.
 */
@Entity
@Table(name = "stock_moves")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class StockMove {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private ProductItem item;

    @Column(nullable = false)
    private LocalDate movedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockMoveKind kind;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    /** The item's stock after this move. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    /** Cost of one at the time (the supplier price). */
    @Column(precision = 12, scale = 2)
    private BigDecimal unitCost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_bill_id")
    private PurchaseBill purchaseBill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_doc_id")
    private SalesDoc salesDoc;

    @Column(length = 300)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private Employee recordedBy;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;
}
