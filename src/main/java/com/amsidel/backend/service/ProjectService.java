/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.amsidel.backend.service.CatalogService.blank;

/**
 * Projects: a customer's job with its owner and team, dates, description, money and tasks; its quotations and
 * invoices (and their payments) are followed inside it. Status: queued, in progress, completed, cancelled; once
 * settled (closed with the money done) only owners and admins may change it, and only they may delete a project.
 * Every change, comment, task step and bill of the project is in its history.
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectTaskRepository taskRepository;
    private final SalesDocRepository salesRepository;
    private final TradePaymentRepository paymentRepository;
    private final EmployeeRepository employeeRepository;
    private final PartyService parties;
    private final TradeNumbers numbers;
    private final TradeAccess access;
    private final TradeHistoryLog history;
    private final ProjectStageService stages;

    // ---- reading ----

    @Transactional(readOnly = true)
    public List<ProjectRow> list(AuthUser me, Long brandId) {
        canRead(me);
        return projectRepository.forBrand(access.scope(me, brandId)).stream().map(p -> {
            List<ProjectTask> tasks = taskRepository.forProject(p.getId());
            return new ProjectRow(p.getId(), p.getCode(), p.getName(), p.getBrand().getCode(), p.getCustomer().getId(),
                    p.getCustomer().getName(), p.getCustomer().getPhone(), p.getStatus(), p.isSettled(),
                    p.getOwner() == null ? null : p.getOwner().getName(), p.getStartDate(), p.getEndDate(),
                    money(p, salesRepository.forProject(p.getId())), tasks.size(),
                    (int) tasks.stream().filter(t -> t.getStatus() == ProjectStatus.COMPLETED).count(),
                    p.getCreatedBy() == null ? null : p.getCreatedBy().getName(), p.getUpdatedAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public ProjectView get(AuthUser me, Long id) {
        canRead(me);
        return view(me, full(me, id));
    }

    /** The people who can own or work on a project of this brand (active staff of it, and people of every brand). */
    @Transactional(readOnly = true)
    public List<PersonRef> people(AuthUser me, Long brandId) {
        Long scope = access.scope(me, brandId);
        return employeeRepository.search(null).stream()
                .filter(e -> e.isActive() && (scope == null || e.getBrand() == null || e.getBrand().getId().equals(scope)))
                .map(PersonRef::of).toList();
    }

    // ---- writing ----

    @Transactional
    public ProjectView create(AuthUser me, ProjectBody body) {
        canWrite(me);
        Project p = new Project();
        p.setBrand(access.brandFor(me, body.getBrandId()));
        p.setCreatedBy(access.me(me));
        fill(me, p, body);
        p.setCode(numbers.next(p.getBrand(), "PRJ", access.today()));
        p.setUpdatedBy(access.me(me));
        projectRepository.saveAndFlush(p);
        log(me, p, "CREATED", "For " + p.getCustomer().getName() + (p.getEstimatedAmount() == null ? "" : ", estimated "
                + PayslipPdf.rupees(p.getEstimatedAmount())) + (p.getOwner() == null ? "" : "; owner " + p.getOwner().getName()));
        return view(me, p);
    }

    @Transactional
    public ProjectView update(AuthUser me, Long id, ProjectBody body) {
        canWrite(me);
        Project p = full(me, id);
        requireEditable(me, p);
        TradeHistoryLog.Changes changes = new TradeHistoryLog.Changes()
                .add("Name", p.getName(), body.getName().strip())
                .add("Description", p.getDescription(), blank(body.getDescription()))
                .add("Start", p.getStartDate(), body.getStartDate()).add("End", p.getEndDate(), body.getEndDate())
                .add("Estimated", p.getEstimatedAmount(), money(body.getEstimatedAmount()))
                .add("Finalized", p.getFinalAmount(), money(body.getFinalAmount()))
                .add("Remarks", p.getRemarks(), blank(body.getRemarks()));
        String customerBefore = p.getCustomer().getName();
        String ownerBefore = p.getOwner() == null ? null : p.getOwner().getName();
        String teamBefore = team(p);
        String statusBefore = p.getStatus();
        fill(me, p, body);
        changes.add("Customer", customerBefore, p.getCustomer().getName())
                .add("Owner", ownerBefore, p.getOwner() == null ? null : p.getOwner().getName())
                .add("Team", teamBefore, team(p)).add("Status", stages.name(statusBefore), stages.name(p.getStatus()));
        p.setUpdatedBy(access.me(me));
        projectRepository.saveAndFlush(p);
        if (!changes.isEmpty()) {
            log(me, p, "UPDATED", changes.toString());
        }
        return view(me, p);
    }

    @Transactional
    public ProjectView setStatus(AuthUser me, Long id, String code, String note) {
        canWrite(me);
        Project p = full(me, id);
        requireEditable(me, p);
        String before = p.getStatus();
        String status = stages.require(code, before).getCode();
        status(p, status);
        p.setUpdatedBy(access.me(me));
        projectRepository.saveAndFlush(p);
        if (!status.equals(before)) {
            log(me, p, "STATUS", stages.name(before) + " → " + stages.name(status) + (blank(note) == null ? "" : ": " + note.strip()));
        }
        return view(me, p);
    }

    /**
     * A sales order of the project was confirmed: the project moves to Confirmed. Only forward: a project already
     * further along (started, completed) or cancelled stays as it is.
     */
    @Transactional
    public void confirmedByOrder(AuthUser me, Project p, String orderNumber) {
        ProjectStage confirmed = stages.find(ProjectStage.CONFIRMED);
        ProjectStage now = stages.find(p.getStatus());
        if (confirmed == null || p.isSettled() || (now != null && (!now.isOpen() || now.getSortOrder() >= confirmed.getSortOrder()))) {
            return;
        }
        String before = p.getStatus();
        status(p, confirmed.getCode());
        p.setUpdatedBy(access.me(me));
        projectRepository.saveAndFlush(p);
        log(me, p, "STATUS", stages.name(before) + " → " + confirmed.getName() + " (sales order " + orderNumber + " confirmed)");
    }

    /** Closed with the money done: locked for everyone but owners and admins (who may settle with money still due). */
    @Transactional
    public ProjectView settle(AuthUser me, Long id, String note) {
        canWrite(me);
        Project p = full(me, id);
        if (p.isSettled()) {
            throw ApiException.conflict("This project is already settled");
        }
        ProjectMoney m = money(p, salesRepository.forProject(id));
        if (m.due().signum() > 0 && !access.admin(me)) {
            throw ApiException.conflict(PayslipPdf.rupees(m.due()) + " is still to be paid; an admin can settle it anyway");
        }
        if (stages.isOpen(p.getStatus())) {
            status(p, ProjectStage.COMPLETED);
        }
        p.setSettled(true);
        p.setSettledAt(Instant.now());
        p.setSettledBy(access.me(me));
        p.setUpdatedBy(access.me(me));
        projectRepository.saveAndFlush(p);
        log(me, p, "SETTLED", "Paid " + PayslipPdf.rupees(m.paid()) + " of " + PayslipPdf.rupees(m.toPay())
                + (m.due().signum() > 0 ? "; " + PayslipPdf.rupees(m.due()) + " left unpaid" : "") + (blank(note) == null ? "" : ": " + note.strip()));
        return view(me, p);
    }

    @Transactional
    public ProjectView reopen(AuthUser me, Long id, String note) {
        access.requireAdmin(me, "Only an admin or owner can reopen a settled project");
        Project p = full(me, id);
        if (!p.isSettled()) {
            throw ApiException.conflict("This project is not settled");
        }
        p.setSettled(false);
        p.setSettledAt(null);
        p.setSettledBy(null);
        p.setUpdatedBy(access.me(me));
        projectRepository.saveAndFlush(p);
        log(me, p, "REOPENED", blank(note));
        return view(me, p);
    }

    /** Owners and admins: the project and its tasks go; its quotations and invoices stay, without the project. */
    @Transactional
    public void delete(AuthUser me, Long id) {
        access.requireAdmin(me, "Only an admin or owner can delete a project");
        Project p = full(me, id);
        List<SalesDoc> bills = salesRepository.forProject(id);
        bills.forEach(d -> d.setProject(null));
        taskRepository.deleteAll(taskRepository.forProject(id));
        log(me, p, "DELETED", p.getName() + " for " + p.getCustomer().getName()
                + (bills.isEmpty() ? "" : "; " + bills.size() + " bill" + (bills.size() == 1 ? "" : "s") + " kept without the project"));
        projectRepository.delete(p);
    }

    /** A remark in the history (allowed on a settled project too). */
    @Transactional
    public ProjectView comment(AuthUser me, Long id, String text) {
        canWrite(me);
        Project p = full(me, id);
        log(me, p, "COMMENT", text.strip());
        return view(me, p);
    }

    // ---- tasks ----

    @Transactional
    public ProjectView addTask(AuthUser me, Long projectId, TaskBody body) {
        canWrite(me);
        Project p = full(me, projectId);
        requireEditable(me, p);
        ProjectTask t = new ProjectTask();
        t.setProject(p);
        t.setCreatedBy(access.me(me));
        t.setSortOrder(taskRepository.forProject(projectId).stream().mapToInt(ProjectTask::getSortOrder).max().orElse(0) + 10);
        fillTask(p, t, body);
        t.setUpdatedBy(access.me(me));
        taskRepository.saveAndFlush(t);
        logTask(me, t, "CREATED", taskSummary(t));
        touch(me, p);
        return view(me, p);
    }

    @Transactional
    public ProjectView updateTask(AuthUser me, Long taskId, TaskBody body) {
        canWrite(me);
        ProjectTask t = task(me, taskId);
        requireEditable(me, t.getProject());
        TradeHistoryLog.Changes changes = new TradeHistoryLog.Changes()
                .add("Title", t.getTitle(), body.getTitle().strip()).add("Description", t.getDescription(), blank(body.getDescription()))
                .add("Registered", t.getRegDate(), body.getRegDate()).add("Start", t.getStartDate(), body.getStartDate())
                .add("End", t.getEndDate(), body.getEndDate()).add("Remarks", t.getRemarks(), blank(body.getRemarks()));
        String assigneeBefore = t.getAssignee() == null ? null : t.getAssignee().getName();
        String billBefore = billLabel(t.getBill());
        ProjectStatus statusBefore = t.getStatus();
        fillTask(t.getProject(), t, body);
        changes.add("Person", assigneeBefore, t.getAssignee() == null ? null : t.getAssignee().getName())
                .add("Bill", billBefore, billLabel(t.getBill())).add("Status", word(statusBefore), word(t.getStatus()));
        t.setUpdatedBy(access.me(me));
        taskRepository.saveAndFlush(t);
        if (!changes.isEmpty()) {
            logTask(me, t, "UPDATED", changes.toString());
        }
        touch(me, t.getProject());
        return view(me, t.getProject());
    }

    @Transactional
    public ProjectView setTaskStatus(AuthUser me, Long taskId, ProjectStatus status, String note) {
        canWrite(me);
        ProjectTask t = task(me, taskId);
        requireEditable(me, t.getProject());
        ProjectStatus before = t.getStatus();
        t.setStatus(status);
        if (status == ProjectStatus.IN_PROGRESS && t.getStartDate() == null) {
            t.setStartDate(access.today());
        }
        if (status == ProjectStatus.COMPLETED && t.getEndDate() == null) {
            t.setEndDate(access.today());
        }
        t.setUpdatedBy(access.me(me));
        taskRepository.saveAndFlush(t);
        if (before != status) {
            logTask(me, t, "STATUS", word(before) + " → " + word(status) + (t.getBill() == null ? "" : " · " + billLabel(t.getBill()))
                    + (blank(note) == null ? "" : ": " + note.strip()));
        }
        touch(me, t.getProject());
        return view(me, t.getProject());
    }

    @Transactional
    public ProjectView deleteTask(AuthUser me, Long taskId) {
        canWrite(me);
        ProjectTask t = task(me, taskId);
        Project p = t.getProject();
        requireEditable(me, p);
        logTask(me, t, "DELETED", taskSummary(t));
        taskRepository.delete(t);
        touch(me, p);
        return view(me, p);
    }

    // ---- helpers ----

    private void fill(AuthUser me, Project p, ProjectBody body) {
        Customer c = parties.customerFull(me, body.getCustomerId());
        if (!c.getBrand().getId().equals(p.getBrand().getId())) {
            throw ApiException.badRequest("Choose a customer of " + p.getBrand().getName());
        }
        if (p.getId() != null && !c.getId().equals(p.getCustomer().getId()) && !salesRepository.forProject(p.getId()).isEmpty()) {
            throw ApiException.conflict("This project already has bills for " + p.getCustomer().getName() + "; its customer cannot change");
        }
        if (body.getStartDate() != null && body.getEndDate() != null && body.getEndDate().isBefore(body.getStartDate())) {
            throw ApiException.badRequest("The end date is before the start date");
        }
        p.setCustomer(c);
        p.setName(body.getName().strip());
        p.setDescription(blank(body.getDescription()));
        p.setOwner(person(p, body.getOwnerId()));
        Set<Employee> team = new LinkedHashSet<>();
        for (Long memberId : body.getMemberIds() == null ? List.<Long>of() : body.getMemberIds()) {
            team.add(person(p, memberId));
        }
        p.getMembers().clear();
        p.getMembers().addAll(team);
        p.setStartDate(body.getStartDate());
        p.setEndDate(body.getEndDate());
        p.setEstimatedAmount(money(body.getEstimatedAmount()));
        p.setFinalAmount(money(body.getFinalAmount()));
        p.setRemarks(blank(body.getRemarks()));
        if (body.getStatus() != null && !body.getStatus().isBlank()) {
            status(p, stages.require(body.getStatus(), p.getId() == null ? null : p.getStatus()).getCode());
        }
    }

    private void fillTask(Project p, ProjectTask t, TaskBody body) {
        if (body.getStartDate() != null && body.getEndDate() != null && body.getEndDate().isBefore(body.getStartDate())) {
            throw ApiException.badRequest("The end date is before the start date");
        }
        t.setTitle(body.getTitle().strip());
        t.setDescription(blank(body.getDescription()));
        t.setStatus(body.getStatus() == null ? (t.getStatus() == null ? ProjectStatus.QUEUED : t.getStatus()) : body.getStatus());
        t.setRegDate(body.getRegDate());
        t.setStartDate(body.getStartDate());
        t.setEndDate(body.getEndDate());
        t.setAssignee(person(p, body.getAssigneeId()));
        if (body.getBillId() == null) {
            t.setBill(null);
        } else {
            SalesDoc bill = salesRepository.findFull(body.getBillId())
                    .filter(d -> d.getProject() != null && d.getProject().getId().equals(p.getId()))
                    .orElseThrow(() -> ApiException.badRequest("Choose a quotation or invoice of this project"));
            t.setBill(bill);
        }
        t.setRemarks(blank(body.getRemarks()));
    }

    private void status(Project p, String status) {
        p.setStatus(status);
        p.setCompletedAt(stages.isDone(status) ? (p.getCompletedAt() == null ? Instant.now() : p.getCompletedAt()) : null);
    }

    private Employee person(Project p, Long id) {
        if (id == null) {
            return null;
        }
        Employee e = employeeRepository.findWithBrandById(id).filter(Employee::isActive)
                .orElseThrow(() -> ApiException.badRequest("Choose people who still work here"));
        if (e.getBrand() != null && !e.getBrand().getId().equals(p.getBrand().getId())) {
            throw ApiException.badRequest(e.getName() + " is not in " + p.getBrand().getName());
        }
        return e;
    }

    private void requireEditable(AuthUser me, Project p) {
        if (p.isSettled() && !access.admin(me)) {
            throw ApiException.forbidden("Project " + p.getCode() + " is settled; only an admin or owner can change it");
        }
    }

    private void touch(AuthUser me, Project p) {
        p.setUpdatedBy(access.me(me));
        p.setUpdatedAt(Instant.now());
        projectRepository.save(p);
    }

    Project full(AuthUser me, Long id) {
        Project p = projectRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id));
        access.check(me, p.getBrand());
        return p;
    }

    private ProjectTask task(AuthUser me, Long id) {
        ProjectTask t = taskRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Task not found: " + id));
        access.check(me, t.getProject().getBrand());
        return t;
    }

    private void canRead(AuthUser me) {
        access.require(me, "You cannot see projects", Menus.PROD_PROJECTS, Menus.PROD_QUOTES, Menus.PROD_INVOICES, Menus.PROD_REPORTS);
    }

    private void canWrite(AuthUser me) {
        access.require(me, "You cannot change projects", Menus.PROD_PROJECTS);
    }

    private void log(AuthUser me, Project p, String action, String details) {
        history.record(me, p.getBrand(), TradeSubject.PROJECT, p.getId(), "Project " + p.getCode() + " · " + p.getName(), action, details, p.getId());
    }

    private void logTask(AuthUser me, ProjectTask t, String action, String details) {
        history.record(me, t.getProject().getBrand(), TradeSubject.PROJECT_TASK, t.getId(), "Task: " + t.getTitle(), action, details,
                t.getProject().getId());
    }

    private static String taskSummary(ProjectTask t) {
        List<String> parts = new ArrayList<>();
        parts.add(word(t.getStatus()));
        parts.add("registered " + t.getRegDate());
        if (t.getStartDate() != null || t.getEndDate() != null) {
            parts.add((t.getStartDate() == null ? "?" : t.getStartDate()) + " to " + (t.getEndDate() == null ? "?" : t.getEndDate()));
        }
        if (t.getAssignee() != null) {
            parts.add(t.getAssignee().getName());
        }
        if (t.getBill() != null) {
            parts.add(billLabel(t.getBill()));
        }
        return String.join(" · ", parts);
    }

    private static String billLabel(SalesDoc d) {
        if (d == null) {
            return null;
        }
        return SalesService.kindName(d.getKind()) + " " + (d.getNumber() == null ? "(draft)" : d.getNumber());
    }

    private static String team(Project p) {
        return p.getMembers().stream().map(Employee::getName).sorted().collect(Collectors.joining(", "));
    }

    static String word(ProjectStatus s) {
        return s == null ? null : switch (s) {
            case QUEUED -> "Queued";
            case IN_PROGRESS -> "In progress";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
        };
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * See ProjectMoney: the quoted figure is the confirmed sales orders, else the accepted quotations, else the latest
     * quotation not rejected.
     */
    static ProjectMoney money(Project p, List<SalesDoc> docs) {
        List<SalesDoc> quotes = docs.stream().filter(d -> d.getKind() == SalesDocKind.QUOTATION && d.getStatus() != SalesDocStatus.REJECTED).toList();
        List<SalesDoc> accepted = quotes.stream().filter(d -> d.getStatus() == SalesDocStatus.ACCEPTED).toList();
        List<SalesDoc> orders = docs.stream().filter(d -> d.getKind() == SalesDocKind.SALES_ORDER && d.getStatus() == SalesDocStatus.ISSUED).toList();
        BigDecimal quoted = !orders.isEmpty() ? PartyService.sum(orders.stream().map(SalesDoc::getTotal).toList())
                : !accepted.isEmpty() ? PartyService.sum(accepted.stream().map(SalesDoc::getTotal).toList())
                : quotes.isEmpty() ? BigDecimal.ZERO.setScale(2) : quotes.getLast().getTotal();
        List<SalesDoc> invoices = docs.stream().filter(d -> d.getKind() == SalesDocKind.INVOICE && d.getStatus() != SalesDocStatus.CANCELLED).toList();
        BigDecimal billed = PartyService.sum(invoices.stream().filter(d -> d.getStatus() == SalesDocStatus.ISSUED).map(SalesDoc::getTotal).toList());
        BigDecimal paid = PartyService.sum(invoices.stream().map(SalesDoc::getPaid).toList());
        BigDecimal writtenOff = PartyService.sum(invoices.stream().map(SalesDoc::getWrittenOff).toList());
        BigDecimal toPay = p.getFinalAmount() != null ? p.getFinalAmount()
                : billed.signum() > 0 ? billed : p.getEstimatedAmount() != null ? p.getEstimatedAmount() : BigDecimal.ZERO.setScale(2);
        return new ProjectMoney(p.getEstimatedAmount(), p.getFinalAmount(), quoted, billed, paid, toPay, toPay.subtract(paid).subtract(writtenOff),
                (int) docs.stream().filter(d -> d.getKind() == SalesDocKind.QUOTATION).count(), invoices.size(), writtenOff);
    }

    private ProjectView view(AuthUser me, Project p) {
        List<SalesDoc> docs = salesRepository.forProject(p.getId());
        List<PaymentView> pays = docs.stream().filter(d -> d.getKind() == SalesDocKind.INVOICE)
                .flatMap(d -> paymentRepository.forInvoice(d.getId()).stream()).map(TradePayments::view)
                .sorted(Comparator.comparing(PaymentView::paidOn).thenComparing(PaymentView::id)).toList();
        Map<Long, List<HistoryView>> taskHistory = history.ofProject(p.getId()).stream()
                .filter(h -> h.subjectType() == TradeSubject.PROJECT_TASK)
                .collect(Collectors.groupingBy(HistoryView::subjectId));
        List<TaskView> tasks = taskRepository.forProject(p.getId()).stream().map(t -> new TaskView(t.getId(), t.getTitle(), t.getDescription(),
                t.getStatus(), t.getRegDate(), t.getStartDate(), t.getEndDate(), PersonRef.of(t.getAssignee()),
                t.getBill() == null ? null : t.getBill().getId(), t.getBill() == null ? null : billLabel(t.getBill()),
                t.getBill() == null ? null : t.getBill().getKind(), t.getRemarks(),
                t.getCreatedBy() == null ? null : t.getCreatedBy().getName(), t.getCreatedAt(),
                t.getUpdatedBy() == null ? null : t.getUpdatedBy().getName(), t.getUpdatedAt(),
                taskHistory.getOrDefault(t.getId(), List.of()))).toList();
        Customer c = p.getCustomer();
        return new ProjectView(p.getId(), p.getCode(), p.getName(), BrandRef.of(p.getBrand()),
                new PartyRef(c.getId(), c.getCode(), c.getName(), c.getPhone(), c.getEmail(), c.getAddress(), c.getGstNumber()),
                p.getDescription(), p.getStatus(), p.isSettled(), p.getSettledAt(), p.getSettledBy() == null ? null : p.getSettledBy().getName(),
                PersonRef.of(p.getOwner()), p.getMembers().stream().map(PersonRef::of).toList(), p.getStartDate(), p.getEndDate(),
                p.getRemarks(), money(p, docs), docs.stream().map(SalesService::row).toList(), pays, tasks, history.ofProject(p.getId()),
                p.getCreatedBy() == null ? null : p.getCreatedBy().getName(), p.getCreatedAt(),
                p.getUpdatedBy() == null ? null : p.getUpdatedBy().getName(), p.getUpdatedAt(), p.getCompletedAt(),
                !p.isSettled() || access.admin(me), access.admin(me));
    }
}
