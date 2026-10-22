package com.amsidel.backend.service;

import com.amsidel.backend.dto.MyTask;
import com.amsidel.backend.dto.TaskRequest;
import com.amsidel.backend.dto.TaskResponse;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.WorkLogRepository;
import com.amsidel.backend.repository.WorkTaskRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Tasks that owners and admins create and assign; employees log hours only against open tasks assigned to them.
 * Owners and group admins see every task; a brand admin sees group-wide tasks and their brand's, and assigns
 * only people of their brand.
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private static final String PREFIX = "TSK-";

    private final WorkTaskRepository taskRepository;
    private final EmployeeRepository employeeRepository;
    private final BrandRepository brandRepository;
    private final WorkLogRepository logRepository;

    @Transactional(readOnly = true)
    public List<TaskResponse> list(AuthUser me, TaskStatus status) {
        Map<Long, BigDecimal> hours = hoursPerTask();
        return taskRepository.search(status).stream()
                .filter(t -> inScope(me, t.getBrand()))
                .map(t -> TaskResponse.from(t, hours.getOrDefault(t.getId(), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyTask> mine(AuthUser me) {
        return taskRepository.assignedTo(me.id(), TaskStatus.OPEN).stream().map(MyTask::from).toList();
    }

    @Transactional
    public TaskResponse create(AuthUser me, TaskRequest request) {
        WorkTask task = new WorkTask();
        task.setCreatedBy(employeeRepository.findById(me.id())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + me.id())));
        apply(me, task, request);
        return TaskResponse.from(taskRepository.save(task), BigDecimal.ZERO);
    }

    @Transactional
    public TaskResponse update(AuthUser me, Long id, TaskRequest request) {
        WorkTask task = taskRepository.findFull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + id));
        if (!inScope(me, task.getBrand())) {
            throw ApiException.forbidden("You can only change tasks of your own brand");
        }
        apply(me, task, request);
        return TaskResponse.from(task, hoursPerTask().getOrDefault(task.getId(), BigDecimal.ZERO));
    }

    /**
     * The task a work log entry may use: it must exist, be open and be assigned to this person.
     * Used by WorkLogService when an entry is added or moved to another task.
     */
    WorkTask loggable(Long employeeId, String code) {
        WorkTask task = taskRepository.findByCode(code.trim())
                .orElseThrow(() -> ApiException.badRequest("Task " + code.trim().toUpperCase(Locale.ROOT) + " does not exist"));
        if (task.getStatus() != TaskStatus.OPEN) {
            throw ApiException.badRequest("Task " + task.getCode() + " is closed");
        }
        if (task.getAssignees().stream().noneMatch(e -> e.getId().equals(employeeId))) {
            throw ApiException.badRequest("Task " + task.getCode() + " is not assigned to you");
        }
        return task;
    }

    private void apply(AuthUser me, WorkTask task, TaskRequest request) {
        String code = request.getCode() == null || request.getCode().isBlank() ? null : request.getCode().trim().toUpperCase(Locale.ROOT);
        if (code == null) {
            code = task.getCode() != null ? task.getCode() : nextCode();
        } else if (!code.equals(task.getCode()) && taskRepository.existsByCodeIgnoreCase(code)) {
            throw ApiException.conflict("Task ID " + code + " is already used");
        }

        Brand brand = request.getBrandId() == null ? null : brandRepository.findById(request.getBrandId())
                .orElseThrow(() -> ApiException.badRequest("Brand not found: " + request.getBrandId()));
        if (!me.isOwner() && !me.isGroupAdmin() && (brand == null || !brand.getId().equals(me.brandId()))) {
            throw ApiException.forbidden("Choose your own brand for this task");
        }

        Set<Employee> assignees = new LinkedHashSet<>();
        for (Long id : new LinkedHashSet<>(request.getAssigneeIds())) {
            Employee e = employeeRepository.findWithBrandById(id)
                    .orElseThrow(() -> ApiException.badRequest("Employee not found: " + id));
            boolean alreadyOn = task.getAssignees().stream().anyMatch(a -> a.getId().equals(id));
            if (!e.isActive() && !alreadyOn) {
                throw ApiException.badRequest(e.getName() + "'s account is disabled");
            }
            if (!inScope(me, e.getBrand())) {
                throw ApiException.forbidden("You can only assign people of your own brand");
            }
            assignees.add(e);
        }

        task.setCode(code);
        task.setTitle(request.getTitle().trim());
        task.setDescription(request.getDescription() == null || request.getDescription().isBlank() ? null : request.getDescription().trim());
        task.setStatus(request.getStatus() != null ? request.getStatus() : TaskStatus.OPEN);
        task.setBrand(brand);
        task.getAssignees().clear();
        task.getAssignees().addAll(assignees);
    }

    /** TSK-0001, TSK-0002 ... (skips any taken by hand). */
    private String nextCode() {
        int next = taskRepository.findTopByCodeStartingWithOrderByCodeDesc(PREFIX)
                .map(t -> t.getCode().substring(PREFIX.length()))
                .filter(rest -> rest.matches("\\d+"))
                .map(rest -> Integer.parseInt(rest) + 1)
                .orElse(1);
        String code = String.format("%s%04d", PREFIX, next);
        while (taskRepository.existsByCodeIgnoreCase(code)) {
            code = String.format("%s%04d", PREFIX, ++next);
        }
        return code;
    }

    private static boolean inScope(AuthUser me, Brand brand) {
        return me.isOwner() || me.brandId() == null || brand == null || me.brandId().equals(brand.getId());
    }

    private Map<Long, BigDecimal> hoursPerTask() {
        Map<Long, BigDecimal> hours = new HashMap<>();
        for (Object[] row : logRepository.hoursPerTask()) {
            hours.put((Long) row[0], (BigDecimal) row[1]);
        }
        return hours;
    }
}
