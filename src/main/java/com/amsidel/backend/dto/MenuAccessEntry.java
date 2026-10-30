/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import com.amsidel.backend.entity.AccessSubject;

import java.util.List;

/**
 * The checkpoints given to one person, user type or designation ("*" = every menu). For a person, label is
 * "Name · user ID · employee number".
 */
public record MenuAccessEntry(AccessSubject subjectType, String subjectKey, String label, List<String> checkpoints) {
}
