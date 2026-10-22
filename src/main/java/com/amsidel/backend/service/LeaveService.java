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
 * Leave balances, applications and approvals.
 * <p>
 * Who decides whose leave: a manager decides for employees of their brand; an admin for managers and
 * employees of their brand (every brand when the admin has none); an owner for everyone. Nobody decides
 * their own; an owner's own leave is approved on submission. The same people grant comp-off and extra days.
 */
@Service
public class LeaveService {

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final List<LeaveStatus> ACTIVE = List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED);
    private static final int MAX_SPAN_DAYS = 90;

    private final LeaveTypeRepository typeRepository;
    private final LeaveRequestRepository requestRepository;
    private final LeaveGrantRepository grantRepository;
    private final RestrictedHolidayRepository holidayRepository;
    private final EmployeeRepository employeeRepository;
    private final Set<DayOfWeek> weeklyOffs;
    private final ZoneId zone;

    public LeaveService(LeaveTypeRepository typeRepository,
                        LeaveRequestRepository requestRepository,
                        LeaveGrantRepository grantRepository,
                        RestrictedHolidayRepository holidayRepository,
                        EmployeeRepository employeeRepository,
                        @Value("${app.leave.weekly-offs}") List<DayOfWeek> weeklyOffs,
                        @Value("${app.leave.zone}") ZoneId zone) {
        this.typeRepository = typeRepository;
        this.requestRepository = requestRepository;
        this.grantRepository = grantRepository;
        this.holidayRepository = holidayRepository;
        this.employeeRepository = employeeRepository;
        this.weeklyOffs = EnumSet.copyOf(weeklyOffs);
        this.zone = zone;
    }

    // ---- the signed-in person's own leave ----

    @Transactional(readOnly = true)
    public List<LeaveBalance> myBalances(AuthUser me, Integer year) {
        return balances(employee(me.id()), yearOr(year));
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> myRequests(AuthUser me, Integer year) {
        return requestRepository.forEmployee(me.id(), yearOr(year)).stream()
                .map(r -> LeaveRequestResponse.from(r, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveGrantResponse> myGrants(AuthUser me, Integer year) {
        return grantRepository.forEmployee(me.id(), yearOr(year)).stream().map(LeaveGrantResponse::from).toList();
    }

    @Transactional
    public LeaveRequestResponse apply(AuthUser me, LeaveApplyRequest request) {
        Employee employee = employee(me.id());
        LeaveType type = typeRepository.findById(request.getLeaveTypeId())
                .filter(LeaveType::isActive)
                .orElseThrow(() -> ApiException.badRequest("Choose a leave type"));
        LocalDate from = request.getFromDate();
        LocalDate to = request.getToDate();
        boolean halfDay = request.isHalfDay();

        if (to.isBefore(from)) {
            throw ApiException.badRequest("The end date is before the start date");
        }
        if (from.getYear() != to.getYear()) {
            throw ApiException.badRequest("Leave cannot run into the next year; apply for each year separately");
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

        BigDecimal days = halfDay ? HALF : workingDays(from, to);
        if (days.signum() == 0) {
            throw ApiException.badRequest("The chosen dates are all weekly offs; no leave is needed");
        }
        if (requestRepository.countOverlapping(employee.getId(), ACTIVE, from, to) > 0) {
            throw ApiException.conflict("You already have leave on some of these dates");
        }
        if (type.getAllocation() != LeaveAllocation.UNLIMITED) {
            BigDecimal available = balanceOf(employee, type, from.getYear()).available();
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
        if (me.isOwner()) {
            // owners have nobody above them to approve
            leave.setStatus(LeaveStatus.APPROVED);
            leave.setDecidedBy(employee);
            leave.setDecidedAt(Instant.now());
            leave.setDecisionNote("Owner's leave, approved automatically");
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

    /** Requests of the people {@code me} decides for; available balance is filled in for pending ones. */
    @Transactional(readOnly = true)
    public List<LeaveRequestResponse> requestsToDecide(AuthUser me, LeaveStatus status, Long brandId) {
        if (!me.isOwner() && !me.isGroupAdmin()) {
            if (brandId != null && !brandId.equals(me.brandId())) {
                throw ApiException.forbidden("You can only see leave of your own brand");
            }
            brandId = me.brandId();
        }
        Map<String, BigDecimal> available = new HashMap<>();
        return requestRepository.search(status, brandId).stream()
                .filter(r -> canDecide(me, r.getEmployee()))
                .map(r -> LeaveRequestResponse.from(r, r.getStatus() == LeaveStatus.PENDING
                        && r.getLeaveType().getAllocation() != LeaveAllocation.UNLIMITED
                        ? available.computeIfAbsent(r.getEmployee().getId() + ":" + r.getLeaveType().getId(),
                        k -> balanceOf(r.getEmployee(), r.getLeaveType(), r.getLeaveYear()).available())
                        : null))
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
        if (approve && leave.getLeaveType().getAllocation() != LeaveAllocation.UNLIMITED
                && balanceOf(leave.getEmployee(), leave.getLeaveType(), leave.getLeaveYear()).available().signum() < 0) {
            throw ApiException.badRequest("Not enough " + leave.getLeaveType().getName()
                    + " left for this request any more");
        }
        leave.setStatus(approve ? LeaveStatus.APPROVED : LeaveStatus.REJECTED);
        leave.setDecidedBy(employee(me.id()));
        leave.setDecidedAt(Instant.now());
        leave.setDecisionNote(trimmed);
        return LeaveRequestResponse.from(leave, null);
    }

    /** Active people whose leave {@code me} decides, by name. */
    @Transactional(readOnly = true)
    public List<TeamMember> team(AuthUser me) {
        Long brandId = me.isOwner() || me.isGroupAdmin() ? null : me.brandId();
        return employeeRepository.search(brandId).stream()
                .filter(e -> e.isActive() && canDecide(me, e))
                .map(TeamMember::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveBalance> balancesOf(AuthUser me, Long employeeId, Integer year) {
        Employee employee = employee(employeeId);
        checkCanView(me, employee);
        return balances(employee, yearOr(year));
    }

    @Transactional(readOnly = true)
    public List<LeaveGrantResponse> grantsOf(AuthUser me, Long employeeId, Integer year) {
        checkCanView(me, employee(employeeId));
        return grantRepository.forEmployee(employeeId, yearOr(year)).stream().map(LeaveGrantResponse::from).toList();
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
        if (request.getDays().remainder(HALF).signum() != 0) {
            throw ApiException.badRequest("Grant whole or half days, e.g. 1 or 1.5");
        }
        int year = request.getYear() != null ? request.getYear()
                : request.getWorkedOn() != null ? request.getWorkedOn().getYear() : today().getYear();

        LeaveGrant grant = new LeaveGrant();
        grant.setEmployee(employee);
        grant.setLeaveType(type);
        grant.setLeaveYear(year);
        grant.setDays(request.getDays().setScale(1, RoundingMode.UNNECESSARY));
        grant.setWorkedOn(request.getWorkedOn());
        grant.setReason(request.getReason().trim());
        grant.setGrantedBy(employee(me.id()));
        return LeaveGrantResponse.from(grantRepository.save(grant));
    }

    // ---- rules ----

    static boolean canDecide(AuthUser me, Employee target) {
        if (me.id().equals(target.getId())) {
            return false;
        }
        Long brandId = target.getBrand() == null ? null : target.getBrand().getId();
        boolean staff = target.getRole() == Role.MANAGER || target.getRole() == Role.EMPLOYEE;
        return switch (me.role()) {
            case OWNER -> true;
            case ADMIN -> staff && (me.brandId() == null || me.brandId().equals(brandId));
            case MANAGER -> target.getRole() == Role.EMPLOYEE && me.brandId() != null && me.brandId().equals(brandId);
            case EMPLOYEE -> false;
        };
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

    /** Days from..to, both included, without weekly offs. */
    private BigDecimal workingDays(LocalDate from, LocalDate to) {
        long count = from.datesUntil(to.plusDays(1)).filter(d -> !weeklyOffs.contains(d.getDayOfWeek())).count();
        return BigDecimal.valueOf(count).setScale(1);
    }

    private List<LeaveBalance> balances(Employee employee, int year) {
        List<LeaveRequest> requests = requestRepository.forEmployee(employee.getId(), year);
        List<LeaveGrant> grants = grantRepository.forEmployee(employee.getId(), year);
        Set<Long> usedTypes = requests.stream().map(r -> r.getLeaveType().getId()).collect(Collectors.toSet());
        return typeRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .filter(t -> t.isActive() || usedTypes.contains(t.getId()))
                .map(t -> balance(t, requests, grants))
                .toList();
    }

    private LeaveBalance balanceOf(Employee employee, LeaveType type, int year) {
        return balance(type, requestRepository.forEmployee(employee.getId(), year),
                grantRepository.forEmployee(employee.getId(), year));
    }

    private static LeaveBalance balance(LeaveType type, List<LeaveRequest> requests, List<LeaveGrant> grants) {
        BigDecimal used = sum(requests, type, LeaveStatus.APPROVED);
        BigDecimal pending = sum(requests, type, LeaveStatus.PENDING);
        BigDecimal granted = grants.stream()
                .filter(g -> g.getLeaveType().getId().equals(type.getId()))
                .map(LeaveGrant::getDays)
                .reduce(BigDecimal.ZERO.setScale(1), BigDecimal::add);
        int quota = type.getAllocation() == LeaveAllocation.ANNUAL && type.getAnnualQuota() != null
                ? type.getAnnualQuota() : 0;
        boolean unlimited = type.getAllocation() == LeaveAllocation.UNLIMITED;
        BigDecimal entitled = unlimited ? null : granted.add(BigDecimal.valueOf(quota));
        BigDecimal available = unlimited ? null : entitled.subtract(used).subtract(pending);
        return new LeaveBalance(type.getId(), type.getCode(), type.getName(), type.getAllocation(),
                type.isHalfDayAllowed(), type.isRestrictedDatesOnly(), quota, granted, entitled, used, pending,
                available);
    }

    private static BigDecimal sum(List<LeaveRequest> requests, LeaveType type, LeaveStatus status) {
        return requests.stream()
                .filter(r -> r.getStatus() == status && r.getLeaveType().getId().equals(type.getId()))
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

    private int yearOr(Integer year) {
        return year != null ? year : today().getYear();
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
