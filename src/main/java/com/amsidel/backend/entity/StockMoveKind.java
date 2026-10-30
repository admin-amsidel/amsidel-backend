/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
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
