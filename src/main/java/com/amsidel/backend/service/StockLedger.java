/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.ProductItemRepository;
import com.amsidel.backend.repository.StockMoveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Every change to stock goes through here: the item is locked, its totals change, and a StockMove records it.
 * Stock in raises totalIn, stock out raises totalOut; on hand = in - out. Items that do not track stock only get
 * the record.
 */
@Component
@RequiredArgsConstructor
public class StockLedger {

    private final ProductItemRepository itemRepository;
    private final StockMoveRepository moveRepository;

    /** quantity: + in, - out. allowShort: let stock go below 0 (cancelling a purchase whose items were sold). */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMove move(Long itemId, StockMoveKind kind, BigDecimal quantity, LocalDate on, BigDecimal unitCost,
                          PurchaseBill bill, SalesDoc doc, String note, Employee by, boolean allowShort) {
        ProductItem item = itemRepository.lockById(itemId).orElseThrow(() -> ApiException.badRequest("Item not found"));
        BigDecimal q = quantity.setScale(2, java.math.RoundingMode.HALF_UP);
        if (item.isTrackStock() && q.signum() < 0 && !allowShort && item.getOnHand().add(q).signum() < 0) {
            throw ApiException.conflict("Only " + plain(item.getOnHand()) + " " + item.getUnit() + " of " + item.getCode()
                    + " (" + item.getName() + ") in stock; " + plain(q.negate()) + " needed");
        }
        if (q.signum() > 0) {
            item.setTotalIn(item.getTotalIn().add(q));
        } else {
            item.setTotalOut(item.getTotalOut().add(q.negate()));
        }
        item.setOnHand(item.getTotalIn().subtract(item.getTotalOut()));
        StockMove m = new StockMove();
        m.setItem(item);
        m.setKind(kind);
        m.setQuantity(q);
        m.setMovedOn(on);
        m.setBalanceAfter(item.getOnHand());
        m.setUnitCost(unitCost == null ? item.getSupplierPrice() : unitCost);
        m.setPurchaseBill(bill);
        m.setSalesDoc(doc);
        m.setNote(note);
        m.setRecordedBy(by);
        return moveRepository.save(m);
    }

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
