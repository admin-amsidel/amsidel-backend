package com.amsidel.backend.dto;

import com.amsidel.backend.entity.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Create or change a task and who it is assigned to. */
@Data
public class TaskRequest {

    /** Empty = the next free TSK-0001 style code. */
    @Size(max = 50)
    @Pattern(regexp = "^$|^[A-Za-z0-9._#/:-]+$", message = "Task ID may use letters, numbers and . _ # / : - (no spaces)")
    private String code;

    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 2000)
    private String description;

    /** Defaults to OPEN. */
    private TaskStatus status;

    /** Empty = a group-wide task. */
    private Long brandId;

    /** Employees who may log hours against it. */
    private List<Long> assigneeIds = new ArrayList<>();
}
