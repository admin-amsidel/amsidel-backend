package com.amsidel.backend.entity;

/** Why stock changed. */
public enum StockMoveKind {
    OPENING,
    PURCHASE,
    PURCHASE_CANCEL,
    SALE,
    SALE_CANCEL,
    ADJUST_IN,
    ADJUST_OUT
}
