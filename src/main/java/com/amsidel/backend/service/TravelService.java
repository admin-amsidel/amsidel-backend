/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.TravelDtos.*;
import com.amsidel.backend.entity.Customer;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.TravelClaim;
import com.amsidel.backend.entity.TravelClaim.Status;
import com.amsidel.backend.entity.TravelStop;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.TravelClaimRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Travel allowance. Each person has a rate per km, set on Allowance rates. A claim is typed in (the Manual entry tick) or
 * tracked as a GPS trip (the GPS trip tick): started with a GPS fix, stops added on the way, completed back at the office;
 * its distance then comes from the route through the stops, which the person confirms (changing it needs its own tick).
 * The amount is the distance times the rate at that moment, plus food and other expenses. The claim goes to the person's
 * approver (else the one they report to; else an owner), who approves it, maybe with another amount, or rejects it. An
 * approved claim is moved to Waiting for settlement and then Settled with the payment's details (the Settle tick).
 */
@Service
@RequiredArgsConstructor
public class TravelService {

    private static final Set<Status> ALLOCATED = EnumSet.of(Status.APPROVED, Status.WAITING_SETTLEMENT, Status.SETTLED);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final TravelClaimRepository claims;
    private final EmployeeRepository employees;
    private final MenuAccessService menuAccess;
    private final TeamAccess teamAccess;
    private final RouteDistance routes;
    private final TimeZones timeZones;
    private final com.amsidel.backend.repository.CustomerRepository customers;

    // ---- rates ----

    @Transactional(readOnly = true)
    public List<RateRow> rates(AuthUser me) {
        List<Employee> people = me.isOwner() || menuAccess.can(me, Menus.TA_RATES) && me.role() == com.amsidel.backend.entity.Role.ADMIN && me.brandId() == null
                ? employees.search(null).stream().filter(Employee::isActive).toList()
                : teamAccess.team(me);
        return people.stream()
                .sorted(Comparator.comparing(Employee::getEmpNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(e -> new RateRow(e.getId(), e.getName(), e.getEmpNumber(), e.getBrand() == null ? null : e.getBrand().getCode(),
                        e.getDesignation() == null ? null : e.getDesignation().getName(), ManualSalesService.symbol(e.getBrand()),
                        e.getTravelRatePerKm(), me.isOwner() || !e.getId().equals(me.id())))
                .toList();
    }

    @Transactional
    public RateRow setRate(AuthUser me, Long employeeId, RateBody body) {
        Employee e = employees.findWithBrandById(employeeId).orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));
        if (!teamAccess.canView(me, e) || (!me.isOwner() && e.getId().equals(me.id()))) {
            throw ApiException.forbidden(e.getId().equals(me.id()) ? "You cannot set your own travel allowance" : "You cannot set this person's travel allowance");
        }
        e.setTravelRatePerKm(body.ratePerKm() == null ? null : body.ratePerKm().setScale(2, RoundingMode.HALF_UP));
        return new RateRow(e.getId(), e.getName(), e.getEmpNumber(), e.getBrand() == null ? null : e.getBrand().getCode(),
                e.getDesignation() == null ? null : e.getDesignation().getName(), ManualSalesService.symbol(e.getBrand()), e.getTravelRatePerKm(), true);
    }

    // ---- my claims ----

    @Transactional(readOnly = true)
    public MyTravel mine(AuthUser me, LocalDate from, LocalDate to, Status status) {
        Employee self = person(me.id());
        LocalDate today = LocalDate.now(timeZones.of(me));
        List<TravelClaim> all = claims.forPeople(List.of(me.id()), today.minusYears(2).withDayOfMonth(1), today.plusDays(1));
        List<TravelClaim> shown = filter(all, from, to, status, null);
        TravelClaim open = claims.openTrips(me.id()).stream().findFirst().orElse(null);
        return new MyTravel(self.getTravelRatePerKm(), ManualSalesService.symbol(self.getBrand()), menuAccess.can(me, Menus.TA_MANUAL),
                menuAccess.can(me, Menus.TA_GPS), menuAccess.can(me, Menus.TA_CHANGE_DISTANCE), totals(shown), months(all, today),
                shown.stream().map(c -> view(me, c)).toList(), open == null ? null : view(me, open));
    }

    @Transactional
    public ClaimView addManual(AuthUser me, ManualBody body) {
        require(me, Menus.TA_MANUAL, "You do not have the Manual entry tick for travel claims");
        Employee self = withRate(me);
        LocalDate today = LocalDate.now(timeZones.of(me));
        if (body.meetingDate().isAfter(today)) {
            throw ApiException.badRequest("The meeting date cannot be in the future");
        }
        TravelClaim c = newClaim(self, TravelClaim.Method.MANUAL, body.customerName(), body.meetingDate());
        customer(c, self, body.customerId());
        c.setFromLocation(clean(body.fromLocation()));
        c.setToLocation(clean(body.toLocation()));
        c.setMeetingPoint(clean(body.meetingPoint()));
        c.setNotes(clean(body.notes()));
        c.setDistanceKm(km(body.distanceKm()));
        submit(c, self, body.foodExpense(), body.otherExpense());
        return view(me, claims.save(c));
    }

    @Transactional
    public ClaimView startTrip(AuthUser me, StartBody body) {
        require(me, Menus.TA_GPS, "You do not have the GPS trip tick for travel claims");
        Employee self = withRate(me);
        if (!claims.openTrips(me.id()).isEmpty()) {
            throw ApiException.conflict("Finish or delete the trip you started before starting another");
        }
        LocalDate today = LocalDate.now(timeZones.of(me));
        TravelClaim c = newClaim(self, TravelClaim.Method.GPS, body.customerName(), body.meetingDate() == null ? today : body.meetingDate());
        customer(c, self, body.customerId());
        if (c.getMeetingDate().isAfter(today)) {
            throw ApiException.badRequest("The meeting date cannot be in the future");
        }
        c.setStatus(Status.IN_PROGRESS);
        c.setFromLocation(clean(body.fromLocation()));
        c.setMeetingPoint(clean(body.meetingPoint()));
        c.setNotes(clean(body.notes()));
        addPoint(c, TravelStop.Kind.START, body.fromLocation(), body.fix());
        return view(me, claims.save(c));
    }

    @Transactional
    public ClaimView addStop(AuthUser me, Long id, StopBody body) {
        TravelClaim c = own(me, id);
        if (c.getStatus() != Status.IN_PROGRESS) {
            throw ApiException.conflict("Stops can be added only while the trip is on");
        }
        addPoint(c, TravelStop.Kind.STOP, body.label(), body.fix());
        if (c.getMeetingPoint() == null) {
            c.setMeetingPoint(clean(body.label()));
        }
        return view(me, c);
    }

    /** Back at the office: the end point, and the route's distance for the person to confirm. */
    @Transactional
    public ClaimView completeTrip(AuthUser me, Long id, CompleteBody body) {
        TravelClaim c = own(me, id);
        if (c.getStatus() != Status.IN_PROGRESS) {
            throw ApiException.conflict("This trip is not on");
        }
        addPoint(c, TravelStop.Kind.END, StringUtils.hasText(body.label()) ? body.label() : "Back at the office", body.fix());
        RouteDistance.Result r = routes.of(c.getStops().stream().map(s -> new RouteDistance.Point(s.getLatitude(), s.getLongitude())).toList());
        c.setShortestKm(BigDecimal.valueOf(r.shortestKm()).setScale(2, RoundingMode.HALF_UP));
        c.setLongestKm(BigDecimal.valueOf(r.longestKm()).setScale(2, RoundingMode.HALF_UP));
        c.setDistanceSource(r.source());
        c.setDistanceKm(c.getShortestKm());
        if (c.getToLocation() == null) {
            c.setToLocation(clean(c.getMeetingPoint()));
        }
        c.setStatus(Status.TO_CONFIRM);
        return view(me, c);
    }

    /** The person confirms the distance (changing it needs its own tick); the claim is worked out and sent for approval. */
    @Transactional
    public ClaimView confirmTrip(AuthUser me, Long id, ConfirmBody body) {
        TravelClaim c = own(me, id);
        if (c.getStatus() != Status.TO_CONFIRM) {
            throw ApiException.conflict("Complete the trip before confirming its distance");
        }
        BigDecimal distance = km(body.distanceKm());
        boolean changed = c.getShortestKm() != null && distance.compareTo(c.getShortestKm()) != 0;
        if (changed) {
            require(me, Menus.TA_CHANGE_DISTANCE, "You may not change the distance worked out from GPS. Ask your admin for the Change travel distance tick");
        }
        Employee self = withRate(me);
        c.setDistanceKm(distance);
        c.setDistanceChanged(changed ? Boolean.TRUE : null);
        if (StringUtils.hasText(body.toLocation())) {
            c.setToLocation(clean(body.toLocation()));
        }
        if (StringUtils.hasText(body.meetingPoint())) {
            c.setMeetingPoint(clean(body.meetingPoint()));
        }
        if (StringUtils.hasText(body.notes())) {
            c.setNotes(clean(body.notes()));
        }
        if (c.getToLocation() == null || c.getMeetingPoint() == null) {
            throw ApiException.badRequest("Give where you went and the meeting point");
        }
        submit(c, self, body.foodExpense(), body.otherExpense());
        return view(me, c);
    }

    /** Only before anyone has decided on it. */
    @Transactional
    public void delete(AuthUser me, Long id) {
        TravelClaim c = own(me, id);
        if (!EnumSet.of(Status.IN_PROGRESS, Status.TO_CONFIRM, Status.SUBMITTED).contains(c.getStatus())) {
            throw ApiException.conflict("A claim that has been decided on cannot be deleted");
        }
        claims.delete(c);
    }

    // ---- the team's claims: approval and settlement ----

    @Transactional(readOnly = true)
    /** brandId: one brand's people only (0 = those without a brand); empty = everyone I may see. */
    public TeamTravel team(AuthUser me, LocalDate from, LocalDate to, Status status, Long employeeId, Long brandId) {
        List<Employee> people = scope(me).stream().filter(e -> ofBrand(e, brandId)).toList();
        List<Long> ids = people.stream().map(Employee::getId).toList();
        LocalDate today = LocalDate.now(timeZones.of(me));
        List<TravelClaim> all = ids.isEmpty() ? List.of() : claims.forPeople(ids, today.minusYears(2).withDayOfMonth(1), today.plusDays(1)).stream()
                .filter(c -> c.getStatus() != Status.IN_PROGRESS && c.getStatus() != Status.TO_CONFIRM).toList();
        List<TravelClaim> shown = filter(all, from, to, status, employeeId);
        return new TeamTravel(totals(shown), months(employeeId == null ? all : filter(all, null, null, null, employeeId), today),
                shown.stream().map(c -> view(me, c)).toList(),
                people.stream().map(e -> new Person(e.getId(), e.getName(), e.getEmpNumber())).toList(), menuAccess.can(me, Menus.TA_SETTLE));
    }

    @Transactional
    public ClaimView decide(AuthUser me, Long id, DecideBody body) {
        TravelClaim c = found(id);
        if (!canDecide(me, c)) {
            throw ApiException.forbidden(c.getEmployee().getId().equals(me.id()) ? "You cannot approve your own claim"
                    : "Only " + c.getEmployee().getName() + "'s approver can decide this claim");
        }
        if (c.getStatus() != Status.SUBMITTED && c.getStatus() != Status.APPROVED && c.getStatus() != Status.REJECTED) {
            throw ApiException.conflict("This claim is already waiting for settlement or settled");
        }
        String remarks = clean(body.remarks());
        if (body.approve()) {
            BigDecimal amount = body.approvedAmount() == null ? c.getClaimedAmount() : money(body.approvedAmount());
            if (amount.compareTo(c.getClaimedAmount()) != 0 && remarks == null) {
                throw ApiException.badRequest("Say why the amount is changed");
            }
            c.setApprovedAmount(amount);
            c.setStatus(Status.APPROVED);
        } else {
            if (remarks == null) {
                throw ApiException.badRequest("Say why the claim is rejected");
            }
            c.setApprovedAmount(null);
            c.setStatus(Status.REJECTED);
        }
        c.setDecisionRemarks(remarks);
        c.setDecidedBy(employees.getReferenceById(me.id()));
        c.setDecidedAt(Instant.now());
        return view(me, c);
    }

    @Transactional
    public ClaimView markWaiting(AuthUser me, Long id) {
        TravelClaim c = settleable(me, id);
        if (c.getStatus() != Status.APPROVED) {
            throw ApiException.conflict("Only an approved claim can wait for settlement");
        }
        c.setStatus(Status.WAITING_SETTLEMENT);
        c.setWaitingSince(Instant.now());
        return view(me, c);
    }

    @Transactional
    public ClaimView settle(AuthUser me, Long id, SettleBody body) {
        TravelClaim c = settleable(me, id);
        if (c.getStatus() != Status.APPROVED && c.getStatus() != Status.WAITING_SETTLEMENT) {
            throw ApiException.conflict("Only an approved claim can be settled");
        }
        if (body.paymentDate().isAfter(LocalDate.now(timeZones.of(me)))) {
            throw ApiException.badRequest("The payment date cannot be in the future");
        }
        if (c.getWaitingSince() == null) {
            c.setWaitingSince(Instant.now());
        }
        c.setStatus(Status.SETTLED);
        c.setTransactionId(clean(body.transactionId()));
        c.setPaymentDate(body.paymentDate());
        c.setPaymentAmount(money(body.paymentAmount()));
        c.setSettlementRemarks(clean(body.remarks()));
        c.setSettledBy(employees.getReferenceById(me.id()));
        c.setSettledAt(Instant.now());
        return view(me, c);
    }

    /**
     * A stop's location changed by hand (the Change stop locations tick): one's own trip before it is decided, or as its
     * approver while it waits. The GPS location is kept; the route, and on a sent claim its amount, are worked out again.
     */
    @Transactional
    public ClaimView editStop(AuthUser me, Long id, int sequence, StopEdit body) {
        TravelClaim c = found(id);
        if (!canEditStops(me, c)) {
            throw ApiException.forbidden(menuAccess.can(me, Menus.TA_EDIT_STOPS)
                    ? "This trip's stops can no longer be changed" : "You do not have the Change stop locations tick");
        }
        TravelStop s = c.getStops().stream().filter(x -> x.getSequence() == sequence).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Stop " + sequence + " not found on " + c.getCode()));
        if (s.getOriginalLatitude() == null) {
            s.setOriginalLatitude(s.getLatitude());
            s.setOriginalLongitude(s.getLongitude());
        }
        s.setLatitude(body.latitude());
        s.setLongitude(body.longitude());
        if (StringUtils.hasText(body.label())) {
            s.setLabel(clean(body.label()));
        }
        s.setEditedBy(employees.getReferenceById(me.id()));
        s.setEditedAt(Instant.now());
        if (c.getStatus() != Status.IN_PROGRESS && c.getStops().size() > 1) {
            BigDecimal before = c.getShortestKm();
            RouteDistance.Result r = routes.of(c.getStops().stream().map(x -> new RouteDistance.Point(x.getLatitude(), x.getLongitude())).toList());
            c.setShortestKm(BigDecimal.valueOf(r.shortestKm()).setScale(2, RoundingMode.HALF_UP));
            c.setLongestKm(BigDecimal.valueOf(r.longestKm()).setScale(2, RoundingMode.HALF_UP));
            c.setDistanceSource(r.source());
            // a distance that followed the route follows it again; one typed in by the person stays
            boolean followed = c.getDistanceKm() == null || before == null || c.getDistanceKm().compareTo(before) == 0;
            if (followed) {
                c.setDistanceKm(c.getShortestKm());
                if (c.getStatus() == Status.SUBMITTED && c.getRatePerKm() != null) {
                    c.setTravelAmount(money(c.getDistanceKm().multiply(c.getRatePerKm())));
                    c.setClaimedAmount(money(c.getTravelAmount().add(nz(c.getFoodExpense())).add(nz(c.getOtherExpense()))));
                }
            }
        }
        return view(me, c);
    }

    private boolean canEditStops(AuthUser me, TravelClaim c) {
        if (c.getMethod() != TravelClaim.Method.GPS || !menuAccess.can(me, Menus.TA_EDIT_STOPS)) {
            return false;
        }
        if (c.getEmployee().getId().equals(me.id())) {
            return EnumSet.of(Status.IN_PROGRESS, Status.TO_CONFIRM, Status.SUBMITTED).contains(c.getStatus());
        }
        return c.getStatus() == Status.SUBMITTED && canDecide(me, c);
    }

    /** The project portal's active customers of my brand (every brand's for someone without one), to pick on a claim. */
    @Transactional(readOnly = true)
    public List<CustomerOption> customers(AuthUser me, String q) {
        Employee self = person(me.id());
        Long brandId = self.getBrand() == null ? null : self.getBrand().getId();
        String query = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        String digits = query.replaceAll("\\D", "");
        return customers.forBrand(brandId).stream()
                .filter(Customer::isActive)
                .filter(x -> query.isEmpty() || x.getName().toLowerCase(Locale.ROOT).contains(query)
                        || (x.getCode() != null && x.getCode().toLowerCase(Locale.ROOT).contains(query))
                        || (digits.length() >= 3 && x.getPhone() != null && x.getPhone().replaceAll("\\D", "").contains(digits)))
                .limit(20)
                .map(x -> new CustomerOption(x.getId(), x.getCode(), x.getName(), x.getPhone(), x.getLocation()))
                .toList();
    }

    /** A customer picked from the list: theirs is the name on the claim. It must be of the person's brand. */
    private void customer(TravelClaim c, Employee self, Long customerId) {
        if (customerId == null) {
            return;
        }
        Customer x = customers.findById(customerId).orElseThrow(() -> ApiException.badRequest("That customer does not exist"));
        if (self.getBrand() != null && (x.getBrand() == null || !x.getBrand().getId().equals(self.getBrand().getId()))) {
            throw ApiException.badRequest("That customer is not one of your brand's");
        }
        c.setCustomer(x);
        c.setCustomerName(x.getName());
    }

    // ---- helpers ----

    private TravelClaim newClaim(Employee self, TravelClaim.Method method, String customer, LocalDate meetingDate) {
        TravelClaim c = new TravelClaim();
        Long last = claims.lastId();
        c.setCode("TA-%04d".formatted((last == null ? 0 : last) + 1));
        c.setEmployee(self);
        c.setMethod(method);
        c.setCustomerName(clean(customer));
        c.setMeetingDate(meetingDate);
        return c;
    }

    /** The amount from the distance and the person's rate now, and the claim sent for approval. */
    private static void submit(TravelClaim c, Employee self, BigDecimal food, BigDecimal other) {
        BigDecimal rate = self.getTravelRatePerKm();
        c.setRatePerKm(rate);
        c.setTravelAmount(money(c.getDistanceKm().multiply(rate)));
        c.setFoodExpense(food == null || food.signum() == 0 ? null : money(food));
        c.setOtherExpense(other == null || other.signum() == 0 ? null : money(other));
        c.setClaimedAmount(money(c.getTravelAmount().add(nz(c.getFoodExpense())).add(nz(c.getOtherExpense()))));
        c.setStatus(Status.SUBMITTED);
        c.setSubmittedAt(Instant.now());
    }

    private static void addPoint(TravelClaim c, TravelStop.Kind kind, String label, Fix fix) {
        TravelStop s = new TravelStop();
        s.setClaim(c);
        s.setSequence(c.getStops().size() + 1);
        s.setKind(kind);
        s.setLabel(clean(label));
        s.setLatitude(fix.latitude());
        s.setLongitude(fix.longitude());
        s.setAccuracy(fix.accuracy());
        s.setAt(Instant.now());
        c.getStops().add(s);
    }

    private Employee withRate(AuthUser me) {
        Employee self = person(me.id());
        if (self.getTravelRatePerKm() == null || self.getTravelRatePerKm().signum() <= 0) {
            throw ApiException.badRequest("Your travel allowance per km is not set yet. Ask your admin to set it on Allowance rates");
        }
        return self;
    }

    private Employee person(Long id) {
        return employees.findWithBrandById(id).orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
    }

    private TravelClaim found(Long id) {
        return claims.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Travel claim not found: " + id));
    }

    private TravelClaim own(AuthUser me, Long id) {
        TravelClaim c = found(id);
        if (!c.getEmployee().getId().equals(me.id())) {
            throw ApiException.forbidden("This is not your travel claim");
        }
        return c;
    }

    private TravelClaim settleable(AuthUser me, Long id) {
        TravelClaim c = found(id);
        if (!canSettle(me, c)) {
            throw ApiException.forbidden("You do not have the Settle travel claims tick");
        }
        return c;
    }

    private void require(AuthUser me, String code, String message) {
        if (!menuAccess.can(me, code)) {
            throw ApiException.forbidden(message);
        }
    }

    /** Owners see everyone; others the people they may look at (TeamAccess). */
    private List<Employee> scope(AuthUser me) {
        if (me.isOwner()) {
            return employees.search(null).stream().filter(e -> !e.getId().equals(me.id())).toList();
        }
        return teamAccess.team(me);
    }

    private static boolean ofBrand(Employee e, Long brandId) {
        if (brandId == null) {
            return true;
        }
        return brandId == 0 ? e.getBrand() == null : e.getBrand() != null && e.getBrand().getId().equals(brandId);
    }

    private boolean canDecide(AuthUser me, TravelClaim c) {
        Employee person = c.getEmployee();
        if (person.getId().equals(me.id()) || !EnumSet.of(Status.SUBMITTED, Status.APPROVED, Status.REJECTED).contains(c.getStatus())) {
            return false;
        }
        if (me.isOwner()) {
            return true;
        }
        Employee approver = WorkApprovalService.approverOf(person);
        return approver != null && approver.getId().equals(me.id());
    }

    private boolean canSettle(AuthUser me, TravelClaim c) {
        return !c.getEmployee().getId().equals(me.id()) && menuAccess.can(me, Menus.TA_SETTLE) && teamAccess.canView(me, c.getEmployee());
    }

    private static List<TravelClaim> filter(List<TravelClaim> all, LocalDate from, LocalDate to, Status status, Long employeeId) {
        return all.stream()
                .filter(c -> from == null || !c.getMeetingDate().isBefore(from))
                .filter(c -> to == null || !c.getMeetingDate().isAfter(to))
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> employeeId == null || c.getEmployee().getId().equals(employeeId))
                .toList();
    }

    private static Totals totals(List<TravelClaim> list) {
        BigDecimal pending = ZERO, allocated = ZERO, waiting = ZERO, settled = ZERO;
        for (TravelClaim c : list) {
            if (c.getStatus() == Status.SUBMITTED) {
                pending = pending.add(nz(c.getClaimedAmount()));
            }
            if (ALLOCATED.contains(c.getStatus())) {
                allocated = allocated.add(nz(c.getApprovedAmount()));
            }
            if (c.getStatus() == Status.APPROVED || c.getStatus() == Status.WAITING_SETTLEMENT) {
                waiting = waiting.add(nz(c.getApprovedAmount()));
            }
            if (c.getStatus() == Status.SETTLED) {
                settled = settled.add(nz(c.getPaymentAmount()));
            }
        }
        int count = (int) list.stream().filter(c -> c.getStatus() != Status.IN_PROGRESS && c.getStatus() != Status.TO_CONFIRM).count();
        return new Totals(count, pending, allocated, waiting, settled);
    }

    /** The last 12 months, oldest first. */
    private static List<Month> months(List<TravelClaim> list, LocalDate today) {
        List<Month> out = new ArrayList<>();
        YearMonth now = YearMonth.from(today);
        for (int i = 11; i >= 0; i--) {
            YearMonth m = now.minusMonths(i);
            BigDecimal allocated = list.stream().filter(c -> ALLOCATED.contains(c.getStatus()) && YearMonth.from(c.getMeetingDate()).equals(m))
                    .map(c -> nz(c.getApprovedAmount())).reduce(ZERO, BigDecimal::add);
            BigDecimal settled = list.stream().filter(c -> c.getStatus() == Status.SETTLED && c.getPaymentDate() != null && YearMonth.from(c.getPaymentDate()).equals(m))
                    .map(c -> nz(c.getPaymentAmount())).reduce(ZERO, BigDecimal::add);
            out.add(new Month(m.toString(), MONTH.format(m), allocated, settled));
        }
        return out;
    }

    private ClaimView view(AuthUser me, TravelClaim c) {
        Employee e = c.getEmployee();
        boolean mine = e.getId().equals(me.id());
        return new ClaimView(c.getId(), c.getCode(), e.getId(), e.getName(), e.getEmpNumber(), e.getBrand() == null ? null : e.getBrand().getCode(),
                ManualSalesService.symbol(e.getBrand()), c.getMethod().name(), c.getStatus().name(),
                c.getCustomer() == null ? null : c.getCustomer().getId(), c.getCustomer() == null ? null : c.getCustomer().getCode(), c.getCustomerName(), c.getMeetingDate(),
                c.getFromLocation(), c.getToLocation(), c.getMeetingPoint(), c.getNotes(), c.getDistanceKm(), c.getShortestKm(), c.getLongestKm(),
                c.getDistanceSource(), Boolean.TRUE.equals(c.getDistanceChanged()), c.getRatePerKm(), c.getTravelAmount(), c.getFoodExpense(),
                c.getOtherExpense(), c.getClaimedAmount(), c.getSubmittedAt(), c.getApprovedAmount(), c.getDecisionRemarks(),
                c.getDecidedBy() == null ? null : c.getDecidedBy().getName(), c.getDecidedAt(), c.getWaitingSince(), c.getTransactionId(),
                c.getPaymentDate(), c.getPaymentAmount(), c.getSettlementRemarks(), c.getSettledBy() == null ? null : c.getSettledBy().getName(),
                c.getSettledAt(),
                c.getStops().stream().map(s -> new StopView(s.getSequence(), s.getKind().name(), s.getLabel(), s.getLatitude(), s.getLongitude(),
                        s.getAccuracy(), s.getAt(), s.getOriginalLatitude(), s.getOriginalLongitude(),
                        s.getEditedBy() == null ? null : s.getEditedBy().getName(), s.getEditedAt())).toList(),
                canDecide(me, c), (c.getStatus() == Status.APPROVED || c.getStatus() == Status.WAITING_SETTLEMENT) && canSettle(me, c),
                mine && EnumSet.of(Status.IN_PROGRESS, Status.TO_CONFIRM, Status.SUBMITTED).contains(c.getStatus()), canEditStops(me, c));
    }

    private static BigDecimal km(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static String clean(String s) {
        return StringUtils.hasText(s) ? s.strip() : null;
    }
}
