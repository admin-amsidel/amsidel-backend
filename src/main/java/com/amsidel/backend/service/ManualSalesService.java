/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.ManualSalesDtos.*;
import com.amsidel.backend.dto.TaskRequest;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.ManualSalesEntryRepository;
import com.amsidel.backend.repository.ManualSalesTaskRepository;
import com.amsidel.backend.repository.WorkTaskRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Manual Sales worklog, for brands without the product portal: sales people's own record of the quotations, sales
 * orders and invoices of a task, typed in with their work log. A task here is a work log task (so its hours count like any
 * other and go to the same approver) with its details in tables of its own; nothing here reads or changes real sales
 * documents. Figures count an entry on its work log day, once approved.
 */
@Service
@RequiredArgsConstructor
public class ManualSalesService {

    private static final int MAX_ROWS = 2000;
    private static final int WEEKS = 8;
    private static final int MONTHS = 12;
    private static final int YEARS = 3;
    private static final int FINANCIAL_YEAR_STARTS = 4;
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter EXPORT_DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final TaskService taskService;
    private final ManualSalesTaskRepository details;
    private final ManualSalesEntryRepository entries;
    private final WorkTaskRepository workTasks;
    private final ProjectStageService stages;
    private final MenuAccessService menuAccess;
    private final TeamAccess access;
    private final TradeAccess trade;
    private final EmployeeRepository employees;
    private final TimeZones timeZones;

    /** Which kinds of paper someone sees, each with its own tick. What they may not see is left out here, not just on the page. */
    private record Columns(boolean quotations, boolean salesOrders, boolean invoices) {

        boolean shows(ManualSalesKind kind) {
            return kind == null || switch (kind) {
                case QUOTATION -> quotations;
                case SALES_ORDER -> salesOrders;
                case INVOICE -> invoices;
            };
        }
    }

    private Columns columns(AuthUser me) {
        return new Columns(menuAccess.can(me, Menus.SALES_COL_QUOTATIONS), menuAccess.can(me, Menus.SALES_COL_ORDERS), menuAccess.can(me, Menus.SALES_COL_INVOICES));
    }

    // ---- tasks ----

    @Transactional(readOnly = true)
    public List<TaskView> tasks(AuthUser me, TaskFilter f) {
        Found found = find(me, f);
        Columns c = columns(me);
        return found.tasks().stream().map(t -> taskView(t, found.info().get(t.getId()), found.entries().getOrDefault(t.getId(), List.of()), found.names(), found.zone(), narrowed(f), c)).toList();
    }

    /** Part of a task's entries: an order in one month and its invoice in another, so ordered less invoiced says nothing. */
    private static boolean narrowed(TaskFilter f) {
        return f.from() != null || f.to() != null || f.employeeId() != null;
    }

    private record Found(List<WorkTask> tasks, Map<Long, ManualSalesTask> info, Map<Long, List<ManualSalesEntry>> entries, Map<String, String> names, ZoneId zone) {
    }

    /** The tasks the filters keep, each with its entries in those dates and of that person. */
    private Found find(AuthUser me, TaskFilter f) {
        if (f.from() != null && f.to() != null && f.to().isBefore(f.from())) {
            throw ApiException.badRequest("The start date must be on or before the end date");
        }
        ZoneId zone = timeZones.of(me);
        Map<String, String> names = stageNames();
        List<WorkTask> scoped = workTasks.searchManual(f.status()).stream().filter(t -> TaskService.inScope(me, t.getBrand()))
                .filter(t -> !StringUtils.hasText(f.code()) || t.getCode().equalsIgnoreCase(f.code().strip())).toList();
        List<Long> ids = scoped.stream().map(WorkTask::getId).toList();
        if (ids.isEmpty()) {
            return new Found(List.of(), Map.of(), Map.of(), names, zone);
        }
        Map<Long, ManualSalesTask> info = details.findAllById(ids).stream().collect(Collectors.toMap(ManualSalesTask::getTaskId, Function.identity()));
        Map<Long, List<ManualSalesEntry>> byTask = entries.forTasks(ids).stream()
                .filter(e -> f.employeeId() == null || e.getEmployee().getId().equals(f.employeeId()))
                .filter(e -> inDates(e.getWorkLog().getWorkDate(), f))
                .collect(Collectors.groupingBy(e -> e.getSalesTask().getTaskId()));
        boolean dated = f.from() != null || f.to() != null;
        List<WorkTask> kept = scoped.stream()
                .filter(t -> !dated || byTask.containsKey(t.getId()) || inDates(createdOn(t, zone), f))
                .filter(t -> f.employeeId() == null || byTask.containsKey(t.getId()) || t.getAssignees().stream().anyMatch(a -> a.getId().equals(f.employeeId())))
                .filter(t -> matches(t, info.get(t.getId()), f.q()))
                .toList();
        return new Found(kept, info, byTask, names, zone);
    }

    private static boolean inDates(LocalDate day, TaskFilter f) {
        return day != null && (f.from() == null || !day.isBefore(f.from())) && (f.to() == null || !day.isAfter(f.to()));
    }

    private static boolean matches(WorkTask t, ManualSalesTask i, String q) {
        if (!StringUtils.hasText(q)) {
            return true;
        }
        List<String> values = new ArrayList<>(List.of(t.getCode(), t.getTitle()));
        if (i != null) {
            values.addAll(java.util.stream.Stream.of(i.getCustomerName(), i.getProjectName(), i.getAgentName()).filter(Objects::nonNull).toList());
        }
        t.getAssignees().forEach(a -> values.add(a.getName()));
        return values.stream().anyMatch(v -> has(v, q));
    }

    /** The day it was made, where this person works; created-at is in the server's own time. */
    private static LocalDate createdOn(WorkTask t, ZoneId zone) {
        return t.getCreatedAt() == null ? null : t.getCreatedAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).toLocalDate();
    }

    /** The Excel file: what the page shows, then every entry behind it, waiting and rejected ones too, as these say so. */
    @Transactional(readOnly = true)
    public ExportService.Export exportTasks(AuthUser me, TaskFilter f) {
        Found found = find(me, f);
        String range = f.from() == null && f.to() == null ? "all dates"
                : (f.from() == null ? "up to " + EXPORT_DAY.format(f.to()) : f.to() == null ? "from " + EXPORT_DAY.format(f.from()) : EXPORT_DAY.format(f.from()) + " to " + EXPORT_DAY.format(f.to()));
        String person = f.employeeId() == null ? null : employees.findById(f.employeeId()).map(Employee::getName).orElse(null);
        String scope = range + (person == null ? "" : ", " + person);
        Columns c = columns(me);
        List<String> taskHeads = new ArrayList<>(List.of("Task ID", "Title", "Status", "Brand", "Customer", "Customer phone", "Project", "Agent", "Agent phone", "Assigned to", "Latest status", "Currency"));
        List<Integer> taskWidths = new ArrayList<>(List.of(11, 26, 8, 8, 22, 14, 22, 18, 14, 26, 18, 8));
        if (c.quotations()) {
            taskHeads.add("Quoted");
            taskWidths.add(12);
        }
        if (c.salesOrders()) {
            taskHeads.add("Ordered");
            taskWidths.add(12);
        }
        if (c.invoices()) {
            taskHeads.add("Invoiced");
            taskWidths.add(12);
        }
        if (c.salesOrders() && c.invoices()) {
            taskHeads.add("To invoice");
            taskWidths.add(12);
        }
        taskHeads.addAll(List.of("Created on", "Created by"));
        taskWidths.addAll(List.of(12, 18));
        try (ExcelBook book = new ExcelBook()) {
            ExcelBook.Table tasks = book.sheet("Tasks", "Manual sales tasks, " + scope, narrowed(f) ? "Quoted, ordered and invoiced count approved entries in these dates and of this person only. To invoice shows only with no such filter."
                            : "Quoted, ordered and invoiced count approved entries; to invoice = ordered less invoiced.",
                    taskHeads.toArray(String[]::new), taskWidths.stream().mapToInt(Integer::intValue).toArray());
            ExcelBook.Table rows = book.sheet("Entries", "Manual sales entries, " + scope, "Each work log entry on these tasks, whatever its approval; only approved ones count in the figures.",
                    new String[]{"Work log day", "Task ID", "Title", "Emp no", "Employee", "Brand", "Customer", "Project", "Agent", "Status", "Type", "Number", "Paper date", "Currency", "Amount", "Hours", "What was done", "Approval", "Decided by"},
                    new int[]{12, 11, 26, 10, 22, 8, 22, 22, 18, 18, 12, 16, 12, 8, 12, 8, -40, 10, 18});
            for (WorkTask t : found.tasks()) {
                List<ManualSalesEntry> list = found.entries().getOrDefault(t.getId(), List.of()).stream().filter(e -> c.shows(e.getKind())).toList();
                TaskView v = taskView(t, found.info().get(t.getId()), list, found.names(), found.zone(), narrowed(f), c);
                List<Object> cells = new ArrayList<>(Arrays.asList(v.code(), v.title(), v.status() == TaskStatus.OPEN ? "Open" : "Closed", v.brandCode(), v.customerName(), v.customerPhone(), v.projectName(), v.agentName(), v.agentPhone(),
                        v.assignees().stream().map(Assignee::name).collect(Collectors.joining(", ")), v.latestStage(), v.currencySymbol()));
                if (c.quotations()) {
                    cells.add(v.quoted());
                }
                if (c.salesOrders()) {
                    cells.add(v.ordered());
                }
                if (c.invoices()) {
                    cells.add(v.invoiced());
                }
                if (c.salesOrders() && c.invoices()) {
                    cells.add(v.balance());
                }
                cells.add(v.createdOn());
                cells.add(v.createdByName());
                tasks.row(cells.toArray());
                for (ManualSalesEntry e : list) {
                    Row r = row(e, found.names());
                    rows.row(r.workDate(), r.taskCode(), r.taskTitle(), r.empNumber(), r.employeeName(), r.brandCode(), r.customerName(), r.projectName(), r.agentName(), r.stageName(), r.kindLabel(),
                            r.documentNumber(), r.documentDate(), r.kind() == null ? null : r.currencySymbol(), r.amount(), com.amsidel.backend.service.ExcelBook.Hours.of(e.getWorkLog().getHours()), r.description(),
                            approvalLabel(r.approval()), r.decidedBy());
                }
            }
            tasks.done();
            rows.done();
            String stamp = (f.from() == null ? "" : "_" + f.from()) + (f.to() == null ? "" : "_" + f.to());
            return new ExportService.Export("ManualSalesTasks" + stamp + ".xlsx", book.bytes());
        }
    }

    private static String approvalLabel(ApprovalStatus a) {
        return a == ApprovalStatus.APPROVED ? "Approved" : a == ApprovalStatus.PENDING ? "Waiting" : "Rejected";
    }

    @Transactional
    public TaskView createTask(AuthUser me, TaskBody body) {
        WorkTask task = taskService.createManual(me, request(body));
        ManualSalesTask info = new ManualSalesTask();
        info.setTask(task);
        apply(info, body);
        return taskView(task, details.save(info), List.of(), Map.of(), timeZones.of(me), false, columns(me));
    }

    @Transactional
    public TaskView updateTask(AuthUser me, Long id, TaskBody body) {
        WorkTask task = taskService.updateManual(me, id, request(body));
        ManualSalesTask info = details.findById(id).orElseThrow(() -> new ResourceNotFoundException("Manual sales task not found: " + id));
        apply(info, body);
        return taskView(task, info, entries.forTasks(List.of(id)), stageNames(), timeZones.of(me), false, columns(me));
    }

    @Transactional(readOnly = true)
    public TaskOptions taskOptions(AuthUser me) {
        List<PersonOption> people = employees.search(null).stream()
                .filter(e -> e.isActive() && TaskService.inScope(me, e.getBrand())).map(ManualSalesService::option).toList();
        List<ManualSalesTask> known = known(me);
        List<String> codes = known.stream().map(d -> d.getTask().getCode()).sorted(Comparator.reverseOrder()).toList();
        // those who have left too, as what they sold still shows
        List<PersonOption> everyone = employees.search(null).stream().filter(e -> TaskService.inScope(me, e.getBrand())).map(ManualSalesService::option).toList();
        return new TaskOptions(people, brands(me), names(known, ManualSalesTask::getCustomerName), names(known, ManualSalesTask::getProjectName), names(known, ManualSalesTask::getAgentName), codes, everyone);
    }

    private List<ManualSalesTask> known(AuthUser me) {
        return details.findAll().stream().filter(d -> TaskService.inScope(me, d.getTask().getBrand())).toList();
    }

    private List<BrandOption> brands(AuthUser me) {
        return trade.brands(me).stream().map(b -> new BrandOption(b.getId(), b.getCode(), b.getName())).toList();
    }

    private static List<String> names(List<ManualSalesTask> tasks, Function<ManualSalesTask, String> name) {
        return tasks.stream().map(name).filter(StringUtils::hasText).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static TaskRequest request(TaskBody body) {
        TaskRequest r = new TaskRequest();
        r.setTitle(body.getTitle());
        r.setDescription(body.getDescription());
        r.setStatus(body.getStatus());
        r.setBrandId(body.getBrandId());
        r.setAssigneeIds(body.getAssigneeIds() == null ? List.of() : body.getAssigneeIds());
        return r;
    }

    private static void apply(ManualSalesTask info, TaskBody body) {
        info.setCustomerName(clean(body.getCustomerName()));
        info.setCustomerPhone(Phones.normalize(body.getCustomerPhone(), "Customer phone"));
        info.setProjectName(clean(body.getProjectName()));
        info.setAgentName(clean(body.getAgentName()));
        info.setAgentPhone(Phones.normalize(body.getAgentPhone(), "Agent phone"));
    }

    private static TaskView taskView(WorkTask t, ManualSalesTask info, List<ManualSalesEntry> list, Map<String, String> stageNames, ZoneId zone, boolean narrowed, Columns c) {
        List<ManualSalesEntry> counted = list.stream().filter(e -> e.getWorkLog().counts()).toList();
        BigDecimal ordered = sum(counted, ManualSalesKind.SALES_ORDER);
        BigDecimal invoiced = sum(counted, ManualSalesKind.INVOICE);
        String latest = list.stream().filter(e -> e.getWorkLog().getApproval() != ApprovalStatus.REJECTED && c.shows(e.getKind())).findFirst()
                .map(e -> stageNames.getOrDefault(e.getStageCode(), e.getStageCode())).orElse(null);
        return new TaskView(t.getId(), t.getCode(), t.getTitle(), t.getDescription(), t.getStatus(), t.getBrand() == null ? null : t.getBrand().getId(),
                t.getBrand() == null ? null : t.getBrand().getCode(), symbol(t.getBrand()), info.getCustomerName(), info.getCustomerPhone(), info.getProjectName(),
                info.getAgentName(), info.getAgentPhone(), t.getAssignees().stream().map(a -> new Assignee(a.getId(), a.getName())).sorted(Comparator.comparing(Assignee::name)).toList(), latest,
                c.quotations() ? sum(counted, ManualSalesKind.QUOTATION) : null, c.salesOrders() ? ordered : null, c.invoices() ? invoiced : null,
                narrowed || !c.salesOrders() || !c.invoices() ? null : ordered.subtract(invoiced), t.getCreatedBy().getName(), createdOn(t, zone));
    }

    private static BigDecimal sum(List<ManualSalesEntry> list, ManualSalesKind kind) {
        return list.stream().filter(e -> e.getKind() == kind && e.getAmount() != null).map(ManualSalesEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---- a work log entry ----

    @Transactional(readOnly = true)
    public List<StageOption> stageOptions() {
        return stages.all().stream().filter(ProjectStage::isActive).map(s -> new StageOption(s.getCode(), s.getName(), ManualSalesKind.of(s.getCode()))).toList();
    }

    /**
     * After a work log entry is saved: its sales details, when its task is a Manual Sales one. Starting one needs the
     * tick; keeping an entry already there does not, so that someone can still correct their own after the tick is taken off.
     */
    @Transactional
    public EntryView saveFor(AuthUser me, Employee person, WorkLog log, EntryBody body) {
        WorkTask task = log.getTask();
        ManualSalesEntry entry = entries.findByWorkLogId(log.getId()).orElse(null);
        if (task == null || !task.isManualSalesTask()) {
            if (entry != null) {
                entries.delete(entry);
            }
            return null;
        }
        if (entry == null && !menuAccess.can(me, Menus.MANUAL_SALES_LOG)) {
            throw ApiException.forbidden("You may not log Manual Sales worklog tasks. Ask your admin for \"Manual Sales worklog\" in Menu access.");
        }
        if (body == null || !StringUtils.hasText(body.getStageCode())) {
            throw ApiException.badRequest("Choose the status of the task");
        }
        ProjectStage stage = stages.require(body.getStageCode(), entry == null ? null : entry.getStageCode());
        ManualSalesKind kind = ManualSalesKind.of(stage.getCode());
        Brand brand = task.getBrand() != null ? task.getBrand() : person.getBrand();
        String number = kind == null ? null : clean(body.getDocumentNumber());
        if (kind != null) {
            List<String> missing = new ArrayList<>();
            if (number == null) {
                missing.add("number");
            }
            if (body.getDocumentDate() == null) {
                missing.add("date");
            }
            if (body.getAmount() == null) {
                missing.add("amount");
            }
            if (!missing.isEmpty()) {
                throw ApiException.badRequest("Give the " + kind.label().toLowerCase(Locale.ROOT) + " " + String.join(", ", missing));
            }
            long except = entry == null || entry.getId() == null ? -1 : entry.getId();
            if (entries.sameNumber(kind, number, brand == null ? -1 : brand.getId(), except) > 0) {
                throw ApiException.conflict(kind.label() + " " + number + " is already recorded");
            }
        }
        if (entry == null) {
            entry = new ManualSalesEntry();
            entry.setWorkLog(log);
            entry.setEmployee(person);
        }
        ManualSalesTask info = details.findById(task.getId()).orElseThrow(() -> ApiException.badRequest("Task " + task.getCode() + " has no sales details"));
        entry.setSalesTask(info);
        entry.setBrand(brand);
        entry.setStageCode(stage.getCode());
        entry.setKind(kind);
        entry.setDocumentNumber(number);
        entry.setDocumentDate(kind == null ? null : body.getDocumentDate());
        entry.setAmount(kind == null ? null : body.getAmount());
        return view(entries.save(entry), Map.of(stage.getCode(), stage.getName()));
    }

    /** Before a work log entry goes. */
    @Transactional
    public void removeFor(Long workLogId) {
        entries.deleteForLog(workLogId);
    }

    /** Work log entry id to its sales details, for those that have any. */
    @Transactional(readOnly = true)
    public Map<Long, EntryView> viewsFor(Collection<Long> logIds) {
        if (logIds.isEmpty()) {
            return Map.of();
        }
        List<ManualSalesEntry> found = entries.forLogs(logIds);
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = stageNames();
        return found.stream().collect(Collectors.toMap(e -> e.getWorkLog().getId(), e -> view(e, names)));
    }

    private static EntryView view(ManualSalesEntry e, Map<String, String> stageNames) {
        ManualSalesTask t = e.getSalesTask();
        return new EntryView(e.getStageCode(), stageNames.getOrDefault(e.getStageCode(), e.getStageCode()), e.getKind(), e.getKind() == null ? null : e.getKind().label(),
                e.getDocumentNumber(), e.getDocumentDate(), e.getAmount(), symbol(e.getBrand()), t.getCustomerName(), t.getProjectName(), t.getAgentName());
    }

    private Map<String, String> stageNames() {
        return stages.all().stream().collect(Collectors.toMap(ProjectStage::getCode, ProjectStage::getName));
    }

    // ---- the figures ----

    /** Mine, or (with the details tick) those of everyone I may see, optionally of one brand (0 = people with no brand). */
    @Transactional(readOnly = true)
    public Summary summary(AuthUser me, boolean team, Long brandId) {
        LocalDate today = LocalDate.now(timeZones.of(me));
        List<Long> ids;
        if (team) {
            if (!menuAccess.can(me, Menus.MANUAL_SALES_DETAILS)) {
                throw ApiException.forbidden("Showing your team's figures needs \"Sales, invoice & quotation details\" in Menu access");
            }
            ids = people(me).stream().filter(e -> ofBrand(e, brandId)).map(Employee::getId).toList();
        } else {
            ids = List.of(me.id());
        }
        List<Span> weeks = weeks(today);
        List<Span> months = months(today);
        List<Span> years = years(today);
        LocalDate from = Collections.min(List.of(weeks.getLast().from(), months.getLast().from(), years.getLast().from()));
        List<ManualSalesEntry> counted = ids.isEmpty() ? List.of() : entries.forPeople(ids, from, today).stream()
                .filter(e -> e.getWorkLog().counts() && e.getKind() != null).toList();
        // always one set, so that someone new sees empty periods rather than nothing
        Map<String, List<ManualSalesEntry>> byCurrency = counted.stream().collect(Collectors.groupingBy(e -> symbol(e.getBrand()), TreeMap::new, Collectors.toList()));
        if (byCurrency.isEmpty()) {
            byCurrency.put(mySymbol(me), List.of());
        }
        Columns seen = columns(me);
        List<CurrencyFigures> figures = byCurrency.entrySet().stream().map(c -> new CurrencyFigures(c.getKey(),
                buckets(weeks, c.getValue(), seen), buckets(months, c.getValue(), seen), buckets(years, c.getValue(), seen))).toList();
        return new Summary(today, figures);
    }

    private String mySymbol(AuthUser me) {
        return employees.findWithBrandById(me.id()).map(e -> symbol(e.getBrand())).orElse(CurrencyCode.INR.symbol());
    }

    private record Span(String label, LocalDate from, LocalDate to) {
    }

    /** Monday to Sunday, the latest first. */
    private static List<Span> weeks(LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < WEEKS; i++) {
            LocalDate from = monday.minusWeeks(i);
            LocalDate to = from.plusDays(6);
            spans.add(new Span(from.format(DAY_MONTH) + " – " + to.format(DAY_MONTH), from, to));
        }
        return spans;
    }

    private static List<Span> months(LocalDate today) {
        LocalDate first = today.withDayOfMonth(1);
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < MONTHS; i++) {
            LocalDate from = first.minusMonths(i);
            spans.add(new Span(from.format(MONTH_YEAR), from, from.with(TemporalAdjusters.lastDayOfMonth())));
        }
        return spans;
    }

    /** Financial years, April to March. */
    private static List<Span> years(LocalDate today) {
        LocalDate start = financialYearStart(today);
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < YEARS; i++) {
            LocalDate from = start.minusYears(i);
            spans.add(new Span("FY " + from.getYear() + "-" + String.format("%02d", (from.getYear() + 1) % 100), from, from.plusYears(1).minusDays(1)));
        }
        return spans;
    }

    private static LocalDate financialYearStart(LocalDate day) {
        LocalDate start = LocalDate.of(day.getYear(), FINANCIAL_YEAR_STARTS, 1);
        return day.isBefore(start) ? start.minusYears(1) : start;
    }

    private static List<Bucket> buckets(List<Span> spans, List<ManualSalesEntry> list, Columns c) {
        return spans.stream().map(s -> {
            List<ManualSalesEntry> in = list.stream().filter(e -> {
                LocalDate d = e.getWorkLog().getWorkDate();
                return !d.isBefore(s.from()) && !d.isAfter(s.to());
            }).toList();
            return new Bucket(s.label(), s.from(), s.to(), c.quotations() ? figure(in, ManualSalesKind.QUOTATION) : null, c.salesOrders() ? figure(in, ManualSalesKind.SALES_ORDER) : null,
                    c.invoices() ? figure(in, ManualSalesKind.INVOICE) : null);
        }).toList();
    }

    private static Figure figure(List<ManualSalesEntry> list, ManualSalesKind kind) {
        return new Figure(list.stream().filter(e -> e.getKind() == kind).count(), sum(list, kind));
    }

    // ---- the details page ----

    @Transactional(readOnly = true)
    public DetailOptions detailOptions(AuthUser me) {
        List<Employee> people = people(me);
        Set<Long> ids = people.stream().map(Employee::getId).collect(Collectors.toSet());
        LocalDate today = LocalDate.now(timeZones.of(me));
        // the names on tasks given to the people I may see
        List<ManualSalesTask> known = details.findAll().stream()
                .filter(d -> d.getTask().getAssignees().stream().anyMatch(a -> ids.contains(a.getId()))).toList();
        Columns c = columns(me);
        return new DetailOptions(people.stream().map(ManualSalesService::option).toList(), brands(me), stages.all().stream()
                .map(s -> new StageOption(s.getCode(), s.getName(), ManualSalesKind.of(s.getCode()))).filter(s -> c.shows(s.kind())).toList(),
                names(known, ManualSalesTask::getCustomerName), names(known, ManualSalesTask::getProjectName), names(known, ManualSalesTask::getAgentName),
                financialYearStart(today), today);
    }

    @Transactional(readOnly = true)
    public Details search(AuthUser me, Filter f) {
        LocalDate today = LocalDate.now(timeZones.of(me));
        LocalDate from = f.from() != null ? f.from() : financialYearStart(today);
        LocalDate to = f.to() != null ? f.to() : today;
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The start date must be on or before the end date");
        }
        List<Employee> people = people(me);
        if (f.employeeId() != null && people.stream().noneMatch(e -> e.getId().equals(f.employeeId()))) {
            throw ApiException.forbidden("You can only see your own figures and those of people below you");
        }
        List<Long> ids = people.stream().filter(e -> f.employeeId() == null || e.getId().equals(f.employeeId())).map(Employee::getId).toList();
        Columns seen = columns(me);
        List<ManualSalesEntry> found = ids.isEmpty() ? List.of() : entries.forPeople(ids, from, to).stream()
                .filter(e -> seen.shows(e.getKind()))
                .filter(e -> f.brandId() == null || (e.getBrand() != null && e.getBrand().getId().equals(f.brandId())))
                .filter(e -> f.kind() == null || e.getKind() == f.kind())
                .filter(e -> !StringUtils.hasText(f.stageCode()) || f.stageCode().equals(e.getStageCode()))
                .filter(e -> f.approval() == null || approval(e) == f.approval())
                .filter(e -> has(e.getSalesTask().getCustomerName(), f.customer()) && has(e.getSalesTask().getProjectName(), f.project()) && has(e.getSalesTask().getAgentName(), f.agent()))
                .toList();
        Map<String, String> names = stageNames();
        List<Totals> totals = found.stream().filter(e -> e.getWorkLog().counts()).collect(Collectors.groupingBy(e -> symbol(e.getBrand()), TreeMap::new, Collectors.toList())).entrySet().stream()
                .map(c -> new Totals(c.getKey(), seen.quotations() ? figure(c.getValue(), ManualSalesKind.QUOTATION) : null, seen.salesOrders() ? figure(c.getValue(), ManualSalesKind.SALES_ORDER) : null,
                        seen.invoices() ? figure(c.getValue(), ManualSalesKind.INVOICE) : null))
                .toList();
        return new Details(from, to, found.stream().limit(MAX_ROWS).map(e -> row(e, names)).toList(), totals, found.size() > MAX_ROWS);
    }

    private static ApprovalStatus approval(ManualSalesEntry e) {
        return e.getWorkLog().getApproval() == null ? ApprovalStatus.APPROVED : e.getWorkLog().getApproval();
    }

    private static boolean has(String value, String wanted) {
        return !StringUtils.hasText(wanted) || (value != null && value.toLowerCase(Locale.ROOT).contains(wanted.strip().toLowerCase(Locale.ROOT)));
    }

    private static Row row(ManualSalesEntry e, Map<String, String> names) {
        ManualSalesTask t = e.getSalesTask();
        WorkLog w = e.getWorkLog();
        return new Row(e.getId(), w.getWorkDate(), e.getEmployee().getId(), e.getEmployee().getName(), e.getEmployee().getEmpNumber(), e.getBrand() == null ? null : e.getBrand().getCode(),
                t.getTask().getCode(), t.getTask().getTitle(), t.getCustomerName(), t.getProjectName(), t.getAgentName(), names.getOrDefault(e.getStageCode(), e.getStageCode()),
                e.getKind(), e.getKind() == null ? null : e.getKind().label(), e.getDocumentNumber(), e.getDocumentDate(), e.getAmount(), symbol(e.getBrand()), approval(e),
                w.getDecidedBy() == null ? null : w.getDecidedBy().getName(), w.getDecidedAt(), w.getDescription());
    }

    /** Me and everyone I may see, those who have left too: what they sold still counts. */
    private List<Employee> people(AuthUser me) {
        return employees.search(null).stream().filter(e -> access.canView(me, e)).toList();
    }

    private static boolean ofBrand(Employee e, Long brandId) {
        if (brandId == null) {
            return true;
        }
        return brandId == 0 ? e.getBrand() == null : e.getBrand() != null && e.getBrand().getId().equals(brandId);
    }

    private static PersonOption option(Employee e) {
        return new PersonOption(e.getId(), e.getName(), e.getEmpNumber(), e.getBrand() == null ? null : e.getBrand().getId(), e.getBrand() == null ? null : e.getBrand().getCode());
    }

    static String symbol(Brand brand) {
        CurrencyCode c = brand == null ? null : brand.getCurrency();
        return (c == null ? CurrencyCode.INR : c).symbol();
    }

    private static String clean(String s) {
        return StringUtils.hasText(s) ? s.strip() : null;
    }
}
