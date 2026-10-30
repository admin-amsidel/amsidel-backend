/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/** OPEN tasks can be logged against; CLOSED ones keep their history but take no new hours. */
public enum TaskStatus {
    OPEN,
    CLOSED
}
