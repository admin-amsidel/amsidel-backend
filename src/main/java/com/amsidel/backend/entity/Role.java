/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

/**
 * What a signed-in user may do. OWNER sees the whole group; ADMIN manages people in their brand
 * (or in every brand when they have none); MANAGER and EMPLOYEE only see their own record for now.
 */
public enum Role {
    OWNER,
    ADMIN,
    MANAGER,
    EMPLOYEE
}
