/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.WorkApprovalDtos.*;
import com.amsidel.backend.entity.ApprovalStatus;
import com.amsidel.backend.entity.Attendance;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.WorkLog;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.AttendanceRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.WorkLogRepository;
import com.amsidel.backend.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Approving attendance and work log entries. A person's entries go to their approver, or to the person they report to when
 * no approver is set; with neither, or when that person has Auto approve on, they count at once, as leave does. Owners can
 * also decide, so nothing gets stuck.
 * Nobody decides their own.
 */
@Service
public class WorkApprovalService {

    private final AttendanceRepository attendances;
    private final WorkLogRepository workLogs;
    private final EmployeeRepository employees;
    private final ManualSalesService manualSales;
    private final ZoneId zone;

    public WorkApprovalService(AttendanceRepository attendances, WorkLogRepository workLogs, EmployeeRepository employees, ManualSalesService manualSales,
                               @Value("${app.leave.zone}") ZoneId zone) {
        this.attendances = attendances;
        this.workLogs = workLogs;
        this.employees = employees;
        this.manualSales = manualSales;
        this.zone = zone;
    }

    /** Who decides this person's entries, or null: call inside a transaction. */
    static Employee approverOf(Employee person) {
        Employee approver = person.getApprover();
        if (usable(approver, person)) {
            return approver;
        }
        Employee boss = person.getReportsTo();
        return usable(boss, person) ? boss : null;
    }

    private static boolean usable(Employee who, Employee person) {
        return who != null && who.isActive() && !who.getId().equals(person.getId());
    }

    /** How a new or changed entry starts: waiting, unless there is nobody to ask or the one to ask has Auto approve on. */
    ApprovalStatus startingStatus(Employee person) {
        Employee approver = approverOf(person);
        return approver == null || Boolean.TRUE.equals(approver.getAutoApproveReports()) ? ApprovalStatus.APPROVED : ApprovalStatus.PENDING;
    }

    private static boolean canDecide(AuthUser me, Employee person) {
        if (person.getId().equals(me.id())) {
            return false;
        }
        if (me.isOwner()) {
            return true;
        }
        Employee approver = approverOf(person);
        return approver != null && approver.getId().equals(me.id());
    }

    @Transactional(readOnly = true)
    public Queue queue(AuthUser me) {
        LocalDate today = LocalDate.now(zone);
        List<AttendanceItem> attendance = attendances.waiting(ApprovalStatus.PENDING).stream()
                .filter(a -> canDecide(me, a.getEmployee()))
                .map(a -> new AttendanceItem(a.getId(), person(a.getEmployee()), a.getWorkDate(), a.getWorkMode(), a.getPlace(), a.getCheckInAt(), a.getCheckOutAt(),
                        a.getCheckOutAt() == null ? null : Duration.between(a.getCheckInAt(), a.getCheckOutAt()).toMinutes(),
                        a.getCheckOutNote(), a.getPhotoPath() != null, decidable(a, today)))
                .toList();
        List<WorkLog> waiting = workLogs.waiting(ApprovalStatus.PENDING).stream().filter(w -> canDecide(me, w.getEmployee())).toList();
        // the approver sees what the sales entry says before it counts
        var sales = manualSales.viewsFor(waiting.stream().map(WorkLog::getId).toList());
        List<WorkLogItem> logs = waiting.stream()
                .map(w -> new WorkLogItem(w.getId(), person(w.getEmployee()), w.getWorkDate(), w.getTaskId(),
                        w.getTask() == null ? null : w.getTask().getTitle(), w.getHours(), w.getDescription(), sales.get(w.getId())))
                .toList();
        return new Queue(attendance, logs);
    }

    /** Someone still checked in today has no time to approve yet; a day that has passed can always be decided. */
    private static boolean decidable(Attendance a, LocalDate today) {
        return a.getCheckOutAt() != null || a.getWorkDate().isBefore(today);
    }

    /** All or nothing. An entry already decided, by another owner say, is left as it is. */
    @Transactional
    public Decided decide(AuthUser me, Decision d) {
        LocalDate today = LocalDate.now(zone);
        ApprovalStatus status = d.approve() ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        String note = StringUtils.hasText(d.note()) ? d.note().strip() : null;
        Employee decider = employees.getReferenceById(me.id());
        Instant now = Instant.now();
        int count = 0;
        for (Attendance a : attendances.findAllById(d.attendanceIds() == null ? List.of() : d.attendanceIds())) {
            check(me, a.getEmployee());
            if (a.getApproval() != ApprovalStatus.PENDING) {
                continue;
            }
            if (!decidable(a, today)) {
                throw ApiException.conflict(a.getEmployee().getName() + " is still checked in; decide this after they check out");
            }
            a.setApproval(status);
            a.setDecidedBy(decider);
            a.setDecidedAt(now);
            a.setDecisionNote(note);
            count++;
        }
        for (WorkLog w : workLogs.findAllById(d.workLogIds() == null ? List.of() : d.workLogIds())) {
            check(me, w.getEmployee());
            if (w.getApproval() != ApprovalStatus.PENDING) {
                continue;
            }
            w.setApproval(status);
            w.setDecidedBy(decider);
            w.setDecidedAt(now);
            w.setDecisionNote(note);
            count++;
        }
        return new Decided(count);
    }

    private static void check(AuthUser me, Employee person) {
        if (!canDecide(me, person)) {
            throw ApiException.forbidden(person.getId().equals(me.id()) ? "You cannot approve your own entries"
                    : "Only " + person.getName() + "'s approver can decide this");
        }
    }

    private static Person person(Employee e) {
        return new Person(e.getId(), e.getName(), e.getEmpNumber(), e.getDesignation() == null ? null : e.getDesignation().getName(),
                e.getBrand() == null ? null : e.getBrand().getCode(), e.getPhotoPath(), e.getPhotoUpdatedAt());
    }
}
