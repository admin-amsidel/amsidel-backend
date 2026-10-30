/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.dto;

import java.time.LocalDate;

/** Today's date at the office (Asia/Kolkata) and my day so far, or null before my first check-in. */
public record AttendanceToday(LocalDate date, String zone, AttendanceDaySummary day) {
}
