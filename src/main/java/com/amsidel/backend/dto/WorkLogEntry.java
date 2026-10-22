package com.amsidel.backend.dto;

import com.amsidel.backend.entity.WorkLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A work log entry as the web app shows it; sequence = its order within the day. */
public record WorkLogEntry(
        Long id,
        LocalDate workDate,
        int sequence,
        String taskId,
        /** Title of the task; null for entries logged before tasks were managed in the portal. */
        String taskTitle,
        BigDecimal hours,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** Call inside a transaction: reads the task. */
    public static WorkLogEntry from(WorkLog w) {
        return new WorkLogEntry(w.getId(), w.getWorkDate(), w.getSequence(), w.getTaskId(),
                w.getTask() == null ? null : w.getTask().getTitle(), w.getHours(),
                w.getDescription(), w.getCreatedAt(), w.getUpdatedAt());
    }
}
