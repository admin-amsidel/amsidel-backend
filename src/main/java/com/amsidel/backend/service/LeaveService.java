/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.*;
import com.amsidel.backend.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Leave balances, applications and approvals, within the leave plan of the person's brand (LeavePlanService): they see
 * and apply against the plan in force today only; what is left of a type set to carry forward goes on to the next plan.
 * <p>
 * Who decides whose leave: the person they report to; owners, and admins in their scope, can step in. Nobody decides
 * their own. Leave is approved on submission for owners, for someone with no (active) reporting person, and when that
 * person is set to auto-approve those below them. The same people grant comp-off and extra days.
 */
@Service
public class LeaveService {

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final List<LeaveStatus> ACTIVE = List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED);
    private static final int MAX_SPAN_DAYS = 90;

    private final LeaveTypeRepository typeRepository;
    private final LeaveRequestRepository requestRepository;
    private final LeaveGrantRepository grantRepository;
    private final LeavePlanService planService;
    private final RestrictedHolidayRepository holidayRepository;
    private final OfficeHolidays officeHolidays;
    private final EmployeeRepository employeeRepository;
    private final Set<DayOfWeek> weeklyOffs;
    private final ZoneId zone;

    public LeaveService(LeaveTypeRepository typeRepository,
                        LeaveRequestRepository requestRepository,
                        LeaveGrantRepository grantRepository, LeavePlanService planService,
                        RestrictedHolidayRepository holidayRepository, OfficeHolidays officeHolidays,
                        EmployeeRepository employeeRepository,
                        @Value("${app.leave.weekly-offs}") List<DayOfWeek> weeklyOffs,
                        @Value("${app.leave.zone}") ZoneId zone) {
        this.typeRepository = typeRepository;
        this.requestRepository = requestRepository;
        this.grantRepository = grantRepository;
        this.planService = planService;
        this.holidayRepository = holidayRepository;
        this.officeHolidays = officeHolidays;
        this.employeeRepository = employeeRepository;
        this.weeklyOffs = EnumSet.copyOf(weeklyOffs);
        this.zone = zone;
    }

    // ---- the signed-in person's own leave ----

    @Transactional(readOnly = true)
    public List<LeaveBalance> myBalances(AuthUser me) {
        return balances(employee(me.id()));
    }

    /** The plan in force today for my brand, today's date and my balances in it; future plans are not shown. */
    @Transactional(readOnly = true)
    public LeavePlanDtos.MyCurrent myCurrent(AuthUser me) {
        Employee employee = employee(me.id());
        LeavePlan plan = currentPlan(employee);
        Brand brand = employee.getBrand();
        return new LeavePlanDtos.MyCurrent(plan == null ? null : plan.getId(), brand == null ? null : brand.getCode(),
                brand == null ? null : brand.getName(), plan == null ? null : plan.getValidFrom(), plan == null ? null : plan.getValidUntil(),
                today(), balances(employee));
    }

    /** My requests in the plan in force. */
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> myRequests(AuthUser me) {
        LeavePlan plan = currentPlan(employee(me.id()));
        return requestRepository.forEmployee(me.id()).stream()
                .filter(r -> plan != null && plan.covers(r.getFromDate()))
                .map(r -> LeaveRequestResponse.from(r, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveGrantResponse> myGrants(AuthUser me) {
        return grantsIn(employee(me.id()));
    }

    @Transactional
    public LeaveRequestResponse apply(AuthUser me, LeaveApplyRequest request) {
        Employee employee = employee(me.id());
        LeaveType type = typeRepository.findById(request.getLeaveTypeId())
                .filter(LeaveType::isActive)
                .orElseThrow(() -> ApiException.badRequest("Choose a leave type"));
        LeavePlan plan = currentPlan(employee);
        if (plan == null) {
            throw ApiException.badRequest("No leave plan is in force for your brand; ask your admin");
        }
        LeavePlanLine line = lineOf(plan, type);
        if (line == null) {
            throw ApiException.badRequest(type.getName() + " is not in your brand's leave plan");
        }
        LocalDate from = request.getFromDate();
        LocalDate to = request.getToDate();
        boolean halfDay = request.isHalfDay();

        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end date is before the start date");
        }
        if (!plan.covers(from) || !plan.covers(to)) {
            throw ApiException.badRequest("Leave can be applied for only inside the current leave plan, from " + plan.getValidFrom()
                    + " to " + plan.getValidUntil());
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_SPAN_DAYS) {
            throw ApiException.badRequest("Apply for at most " + MAX_SPAN_DAYS + " days at a time");
        }
        if (halfDay && !from.equals(to)) {
            throw ApiException.badRequest("A half day must be a single date");
        }
        if (halfDay && !type.isHalfDayAllowed()) {
            throw ApiException.badRequest(type.getName() + " cannot be taken as a half day");
        }
        if (type.isRestrictedDatesOnly()) {
            checkRestrictedDate(employee, from, to);
        }

        if (halfDay && isOfficeHoliday(employee, from)) {
            throw ApiException.badRequest(from + " is an office holiday; no leave is needed");
        }
        BigDecimal days = halfDay ? HALF : workingDays(employee, from, to);
        if (days.signum() == 0) {
            throw ApiException.badRequest("The chosen dates are all weekly offs or office holidays; no leave is needed");
        }
        if (requestRepository.countOverlapping(employee.getId(), ACTIVE, from, to) > 0) {
            throw ApiException.conflict("You already have leave on some of these dates");
        }
        if (type.getAllocation() != LeaveAllocation.UNLIMITED) {
            BigDecimal available = balanceOf(employee, plan, type).available();
            if (available.compareTo(days) < 0) {
                throw ApiException.badRequest("Not enough " + type.getName() + ": " + plain(available)
                        + " day(s) available, " + plain(days) + " asked for");
            }
        }

        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(employee);
        leave.setLeaveType(type);
        leave.setFromDate(from);
        leave.setToDate(to);
        leave.setLeaveYear(from.getYear());
        leave.setHalfDay(halfDay);
        leave.setDays(days);
        leave.setReason(trimToNull(request.getReason()));
        String auto = autoApproval(me, employee);
        if (auto != null) {
            leave.setStatus(LeaveStatus.APPROVED);
            leave.setDecidedBy(employee);
            leave.setDecidedAt(Instant.now());
            leave.setDecisionNote(auto);
        } else {
            leave.setStatus(LeaveStatus.PENDING);
        }
        return LeaveRequestResponse.from(requestRepository.save(leave), null);
    }

    /** Pending leave, or approved leave that has not started yet. */
    @Transactional
    public LeaveRequestResponse cancel(AuthUser me, Long id) {
        LeaveRequest leave = request(id);
        if (!leave.getEmployee().getId().equals(me.id())) {
            throw ApiException.forbidden("You can only cancel your own leave");
        }
        boolean upcoming = leave.getStatus() == LeaveStatus.APPROVED && leave.getFromDate().isAfter(today());
        if (leave.getStatus() != LeaveStatus.PENDING && !upcoming) {
            throw ApiException.badRequest("Only pending leave, or approved leave that has not started, can be cancelled");
        }
        leave.setStatus(LeaveStatus.CANCELLED);
        return LeaveRequestResponse.from(leave, null);
    }

    // ---- approvers: owners, admins, managers ----

    /**
     * Requests of the people {@code me} decides for, by status, brand and dates (touching from..to, either may be left
     * out); available balance is filled in for pending ones.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> requestsToDecide(AuthUser me, LeaveStatus status, Long brandId, String brandCode, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> available = new HashMap<>();
        return requestRepository.search(status, brandId).stream()
                .filter(r -> canDecide(me, r.getEmployee()))
                .filter(r -> brandCode == null || brandCode.isBlank() || (r.getEmployee().getBrand() != null && brandCode.equalsIgnoreCase(r.getEmployee().getBrand().getCode())))
                .filter(r -> from == null || !r.getToDate().isBefore(from))
                .filter(r -> to == null || !r.getFromDate().isAfter(to))
                .map(r -> {
                    BigDecimal left = null;
                    if (r.getStatus() == LeaveStatus.PENDING && r.getLeaveType().getAllocation() != LeaveAllocation.UNLIMITED) {
                        left = available.computeIfAbsent(r.getEmployee().getId() + ":" + r.getLeaveType().getId() + ":" + r.getFromDate(),
                                k -> Optional.ofNullable(balanceAt(r.getEmployee(), r.getLeaveType(), r.getFromDate())).map(LeaveBalance::available).orElse(null));
                    }
                    return LeaveRequestResponse.from(r, left);
                })
                .toList();
    }

    @Transactional
    public LeaveRequestResponse decide(AuthUser me, Long id, boolean approve, String note) {
        LeaveRequest leave = request(id);
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw ApiException.badRequest("This request is already " + leave.getStatus().name().toLowerCase(Locale.ROOT));
        }
        if (!canDecide(me, leave.getEmployee())) {
            throw ApiException.forbidden("You cannot decide on this person's leave");
        }
        String trimmed = trimToNull(note);
        if (!approve && trimmed == null) {
            throw ApiException.badRequest("Give a reason when rejecting leave");
        }
        // the balance counts this request as pending already, so below zero means it no longer fits
        if (approve && leave.getLeaveType().getAllocation() != LeaveAllocation.UNLIMITED) {
            LeaveBalance balance = balanceAt(leave.getEmployee(), leave.getLeaveType(), leave.getFromDate());
            if (balance != null && balance.available().signum() < 0) {
                throw ApiException.badRequest("Not enough " + leave.getLeaveType().getName()
                        + " left for this request any more");
            }
        }
        leave.setStatus(approve ? LeaveStatus.APPROVED : LeaveStatus.REJECTED);
        leave.setDecidedBy(employee(me.id()));
        leave.setDecidedAt(Instant.now());
        leave.setDecisionNote(trimmed);
        return LeaveRequestResponse.from(leave, null);
    }

    /** Active people whose leave {@code me} decides, by name (someone reporting to me may be in another brand). */
    @Transactional(readOnly = true)
    public List<TeamMember> team(AuthUser me) {
        return employeeRepository.search(null).stream()
                .filter(e -> e.isActive() && canDecide(me, e))
                .map(TeamMember::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveBalance> balancesOf(AuthUser me, Long employeeId) {
        Employee employee = employee(employeeId);
        checkCanView(me, employee);
        return balances(employee);
    }

    @Transactional(readOnly = true)
    public List<LeaveGrantResponse> grantsOf(AuthUser me, Long employeeId) {
        Employee employee = employee(employeeId);
        checkCanView(me, employee);
        return grantsIn(employee);
    }

    @Transactional
    public LeaveGrantResponse grant(AuthUser me, LeaveGrantRequest request) {
        Employee employee = employee(request.getEmployeeId());
        if (!canDecide(me, employee)) {
            throw ApiException.forbidden("You can only grant leave to people whose leave you approve");
        }
        LeaveType type = typeRepository.findById(request.getLeaveTypeId())
                .filter(LeaveType::isActive)
                .orElseThrow(() -> ApiException.badRequest("Choose a leave type"));
        if (type.getAllocation() == LeaveAllocation.UNLIMITED) {
            throw ApiException.badRequest(type.getName() + " has no balance to add days to");
        }
        LeavePlan plan = currentPlan(employee);
        if (plan == null) {
            throw ApiException.badRequest("No leave plan is in force for " + employee.getName() + "'s brand");
        }
        if (lineOf(plan, type) == null) {
            throw ApiException.badRequest(type.getName() + " is not in the leave plan of " + employee.getName() + "'s brand");
        }
        if (request.getDays().remainder(HALF).signum() != 0) {
            throw ApiException.badRequest("Grant whole or half days, e.g. 1 or 1.5");
        }

        LeaveGrant grant = new LeaveGrant();
        grant.setEmployee(employee);
        grant.setLeaveType(type);
        grant.setPlan(plan);
        grant.setLeaveYear(plan.getValidFrom().getYear());
        grant.setDays(request.getDays().setScale(1, RoundingMode.UNNECESSARY));
        grant.setWorkedOn(request.getWorkedOn());
        grant.setReason(request.getReason().trim());
        grant.setGrantedBy(employee(me.id()));
        return LeaveGrantResponse.from(grantRepository.save(grant));
    }

    // ---- rules ----

    /** The person they report to; owners, and admins in their scope, can step in. Never their own. */
    static boolean canDecide(AuthUser me, Employee target) {
        if (me.id().equals(target.getId())) {
            return false;
        }
        if (target.getReportsTo() != null && me.id().equals(target.getReportsTo().getId())) {
            return true;
        }
        Long brandId = target.getBrand() == null ? null : target.getBrand().getId();
        boolean staff = target.getRole() == Role.MANAGER || target.getRole() == Role.EMPLOYEE;
        return me.isOwner() || (me.role() == Role.ADMIN && staff && (me.brandId() == null || me.brandId().equals(brandId)));
    }

    /** Why this leave needs no decision; null when it goes to the reporting person. */
    private static String autoApproval(AuthUser me, Employee employee) {
        if (me.isOwner()) {
            return "Owner's leave, approved automatically";
        }
        Employee boss = employee.getReportsTo();
        if (boss == null || !boss.isActive()) {
            return "No reporting person, approved automatically";
        }
        if (Boolean.TRUE.equals(boss.getAutoApproveReports())) {
            return "Approved automatically (set for " + boss.getName() + "'s team)";
        }
        // Auto approve on the person's approver covers their leave too
        Employee approver = employee.getApprover();
        return approver != null && approver.isActive() && Boolean.TRUE.equals(approver.getAutoApproveReports())
                ? "Approved automatically (set for " + approver.getName() + "'s people)" : null;
    }

    private void checkCanView(AuthUser me, Employee target) {
        Long brandId = target.getBrand() == null ? null : target.getBrand().getId();
        boolean adminInScope = me.role() == Role.ADMIN && (me.brandId() == null || me.brandId().equals(brandId));
        if (!me.id().equals(target.getId()) && !me.isOwner() && !adminInScope && !canDecide(me, target)) {
            throw ApiException.forbidden("You cannot see this person's leave");
        }
    }

    private void checkRestrictedDate(Employee employee, LocalDate from, LocalDate to) {
        if (!from.equals(to)) {
            throw ApiException.badRequest("A restricted holiday is one day: choose one date from the list");
        }
        Long brandId = employee.getBrand() == null ? null : employee.getBrand().getId();
        boolean listed = holidayRepository.between(from, from).stream()
                .anyMatch(h -> h.getBrand() == null || brandId == null || h.getBrand().getId().equals(brandId));
        if (!listed) {
            throw ApiException.badRequest(from + " is not in the restricted holiday list");
        }
    }

    /** Days from..to, both included, without weekly offs and the person's office holidays. */
    private BigDecimal workingDays(Employee employee, LocalDate from, LocalDate to) {
        Set<LocalDate> closed = officeHolidays.forBrand(brandIdOf(employee), from, to).keySet();
        long count = from.datesUntil(to.plusDays(1))
                .filter(d -> !weeklyOffs.contains(d.getDayOfWeek()) && !closed.contains(d))
                .count();
        return BigDecimal.valueOf(count).setScale(1);
    }

    private boolean isOfficeHoliday(Employee employee, LocalDate day) {
        return !officeHolidays.forBrand(brandIdOf(employee), day, day).isEmpty();
    }

    private static Long brandIdOf(Employee employee) {
        return employee.getBrand() == null ? null : employee.getBrand().getId();
    }

    private LeavePlan currentPlan(Employee employee) {
        return LeavePlanService.covering(planService.ofBrand(brandIdOf(employee)), today());
    }

    private static LeavePlanLine lineOf(LeavePlan plan, LeaveType type) {
        return plan.getLines().stream().filter(l -> l.getLeaveType().getId().equals(type.getId())).findFirst().orElse(null);
    }

    /** The grants counted in the plan in force. */
    private List<LeaveGrantResponse> grantsIn(Employee employee) {
        LeavePlan plan = currentPlan(employee);
        return plan == null ? List.of() : grantRepository.forEmployee(employee.getId()).stream()
                .filter(g -> countsIn(g, plan)).map(LeaveGrantResponse::from).toList();
    }

    /** Balances in the plan in force: the plan's types (one no longer in use only if it has been taken). */
    private List<LeaveBalance> balances(Employee employee) {
        List<LeavePlan> plans = planService.ofBrand(brandIdOf(employee));
        if (LeavePlanService.covering(plans, today()) == null) {
            return List.of();
        }
        return balances(plans, requestRepository.forEmployee(employee.getId()), grantRepository.forEmployee(employee.getId()));
    }

    /** Each person's balances in their brand's plan in force, read for the whole team at once (the dashboard). */
    @Transactional(readOnly = true)
    public Map<Long, List<LeaveBalance>> balancesOfTeam(List<Employee> team) {
        if (team.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = team.stream().map(Employee::getId).toList();
        Map<Long, List<LeaveRequest>> requests = requestRepository.ofPeople(ids).stream().collect(Collectors.groupingBy(r -> r.getEmployee().getId()));
        Map<Long, List<LeaveGrant>> grants = grantRepository.ofPeople(ids).stream().collect(Collectors.groupingBy(g -> g.getEmployee().getId()));
        Map<Long, List<LeavePlan>> plansOf = new HashMap<>();
        Map<Long, List<LeaveBalance>> out = new HashMap<>();
        for (Employee e : team) {
            long brandKey = brandIdOf(e) == null ? 0L : brandIdOf(e);
            List<LeavePlan> plans = plansOf.computeIfAbsent(brandKey, k -> planService.ofBrand(brandIdOf(e)));
            out.put(e.getId(), balances(plans, requests.getOrDefault(e.getId(), List.of()), grants.getOrDefault(e.getId(), List.of())));
        }
        return out;
    }

    private List<LeaveBalance> balances(List<LeavePlan> plans, List<LeaveRequest> requests, List<LeaveGrant> grants) {
        LeavePlan plan = LeavePlanService.covering(plans, today());
        if (plan == null) {
            return List.of();
        }
        Set<Long> usedTypes = requests.stream().filter(r -> plan.covers(r.getFromDate())).map(r -> r.getLeaveType().getId()).collect(Collectors.toSet());
        return plan.getLines().stream()
                .filter(l -> l.getLeaveType().isActive() || usedTypes.contains(l.getLeaveType().getId()))
                .sorted(Comparator.comparingInt((LeavePlanLine l) -> l.getLeaveType().getSortOrder()).thenComparing(l -> l.getLeaveType().getName()))
                .map(l -> balance(plans, plan, l.getLeaveType(), requests, grants))
                .toList();
    }

    private LeaveBalance balanceOf(Employee employee, LeavePlan plan, LeaveType type) {
        return balance(planService.ofBrand(brandIdOf(employee)), plan, type, requestRepository.forEmployee(employee.getId()),
                grantRepository.forEmployee(employee.getId()));
    }

    /** The balance in the plan covering this day; null when none does. */
    private LeaveBalance balanceAt(Employee employee, LeaveType type, LocalDate day) {
        LeavePlan plan = LeavePlanService.covering(planService.ofBrand(brandIdOf(employee)), day);
        return plan == null ? null : balanceOf(employee, plan, type);
    }

    private static LeaveBalance balance(List<LeavePlan> plans, LeavePlan plan, LeaveType type, List<LeaveRequest> requests, List<LeaveGrant> grants) {
        BigDecimal used = sum(requests, plan, type, LeaveStatus.APPROVED);
        BigDecimal pending = sum(requests, plan, type, LeaveStatus.PENDING);
        BigDecimal granted = grants.stream()
                .filter(g -> g.getLeaveType().getId().equals(type.getId()) && countsIn(g, plan))
                .map(LeaveGrant::getDays)
                .reduce(BigDecimal.ZERO.setScale(1), BigDecimal::add);
        LeavePlanLine line = lineOf(plan, type);
        int quota = line != null && line.getDays() != null ? line.getDays() : 0;
        boolean unlimited = type.getAllocation() == LeaveAllocation.UNLIMITED;
        BigDecimal carried = unlimited ? BigDecimal.ZERO.setScale(1) : carriedInto(plans, plan, type, requests, grants);
        BigDecimal entitled = unlimited ? null : granted.add(carried).add(BigDecimal.valueOf(quota));
        BigDecimal available = unlimited ? null : entitled.subtract(used).subtract(pending);
        return new LeaveBalance(type.getId(), type.getCode(), type.getName(), type.getAllocation(),
                type.isHalfDayAllowed(), type.isRestrictedDatesOnly(), quota, carried, granted, entitled, used, pending,
                available);
    }

    /** What is left of this type in the plan before, when that plan carries it forward (at most its limit). */
    private static BigDecimal carriedInto(List<LeavePlan> plans, LeavePlan plan, LeaveType type, List<LeaveRequest> requests, List<LeaveGrant> grants) {
        LeavePlan before = LeavePlanService.before(plans, plan);
        LeavePlanLine line = before == null ? null : lineOf(before, type);
        if (line == null || !line.isCarryForward()) {
            return BigDecimal.ZERO.setScale(1);
        }
        BigDecimal left = balance(plans, before, type, requests, grants).available().max(BigDecimal.ZERO);
        return line.getMaxCarryDays() != null ? left.min(line.getMaxCarryDays()).setScale(1, RoundingMode.DOWN) : left;
    }

    /** A grant counts in its plan; one made before plans counts in the plan starting in its year. */
    private static boolean countsIn(LeaveGrant g, LeavePlan plan) {
        return g.getPlan() != null ? g.getPlan().getId().equals(plan.getId()) : g.getLeaveYear() == plan.getValidFrom().getYear();
    }

    private static BigDecimal sum(List<LeaveRequest> requests, LeavePlan plan, LeaveType type, LeaveStatus status) {
        return requests.stream()
                .filter(r -> r.getStatus() == status && r.getLeaveType().getId().equals(type.getId()) && plan.covers(r.getFromDate()))
                .map(LeaveRequest::getDays)
                .reduce(BigDecimal.ZERO.setScale(1), BigDecimal::add);
    }

    private LeaveRequest request(Long id) {
        return requestRepository.findFull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found: " + id));
    }

    private Employee employee(Long id) {
        return employeeRepository.findWithBrandById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private static String plain(BigDecimal days) {
        return days.stripTrailingZeros().toPlainString();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
