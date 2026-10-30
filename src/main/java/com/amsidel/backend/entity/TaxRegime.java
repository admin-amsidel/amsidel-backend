/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/**
 * How a salary's income tax is set: NEW or OLD regime = worked out from the salary (the Income Tax line follows it);
 * MANUAL = typed in by someone allowed to (the Manual income tax checkpoint).
 */
public enum TaxRegime {
    NEW,
    OLD,
    MANUAL
}
