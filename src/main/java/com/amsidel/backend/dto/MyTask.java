package com.amsidel.backend.dto;

import com.amsidel.backend.entity.WorkTask;

/** An open task assigned to the signed-in person, for the work log's Task ID picker. */
public record MyTask(Long id, String code, String title, String description) {

    public static MyTask from(WorkTask t) {
        return new MyTask(t.getId(), t.getCode(), t.getTitle(), t.getDescription());
    }
}
