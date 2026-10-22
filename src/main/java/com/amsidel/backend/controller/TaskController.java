package com.amsidel.backend.controller;

import com.amsidel.backend.dto.MyTask;
import com.amsidel.backend.dto.TaskRequest;
import com.amsidel.backend.dto.TaskResponse;
import com.amsidel.backend.entity.TaskStatus;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Tasks: /mine with My work log (to log hours); creating and assigning needs the Tasks menu. */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@Checkpoint({Menus.TASKS})
public class TaskController {

    private final TaskService taskService;

    @Checkpoint({Menus.WORKLOG_MY})

    @GetMapping("/mine")
    public List<MyTask> mine(@AuthenticationPrincipal AuthUser me) {
        return taskService.mine(me);
    }

    @GetMapping
    public List<TaskResponse> list(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) TaskStatus status) {
        return taskService.list(me, status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody TaskRequest request) {
        return taskService.create(me, request);
    }

    @PutMapping("/{id}")
    public TaskResponse update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                               @Valid @RequestBody TaskRequest request) {
        return taskService.update(me, id, request);
    }
}
