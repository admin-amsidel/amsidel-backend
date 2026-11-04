/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.LeavePlanDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.LeavePlanRepository;
import com.amsidel.backend.repository.LeaveRequestRepository;
import com.amsidel.backend.repository.LeaveTypeRepository;
import com.amsidel.backend.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Leave plans (Leave settings): each brand's leave for a period, with valid from and until, which types of leave it has,
 * how many days, and whether what is left carries forward. A brand's plans never overlap. The next plan can be added
 * only in the last 10 days of the current one, and only one can wait; owners and admins are told 10 days before a plan
 * ends (LeaveExpiryNotifier, and the alert in the portal). People without a brand have plans of their own.
 */
@Service
public class LeavePlanService {

    /** Before a plan ends: owners and admins are told, and the next plan can be added. */
    public static final int NOTICE_DAYS = 10;
    private static final long MAX_SPAN_DAYS = 1100;
    private static final List<LeaveStatus> ACTIVE = List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED);

    private final LeavePlanRepository plans;
    private final LeaveTypeRepository types;
    private final BrandRepository brands;
    private final LeaveRequestRepository requests;
    private final ZoneId zone;

    public LeavePlanService(LeavePlanRepository plans, LeaveTypeRepository types, BrandRepository brands,
                            LeaveRequestRepository requests, @Value("${app.leave.zone}") ZoneId zone) {
        this.plans = plans;
        this.types = types;
        this.brands = brands;
        this.requests = requests;
        this.zone = zone;
    }

    // ---- what other parts need ----

    /** The brand's plans, earliest first; brandId null = the group-level people. */
    List<LeavePlan> ofBrand(Long brandId) {
        return plans.allFull().stream().filter(p -> Objects.equals(brandIdOf(p), brandId)).toList();
    }

    static LeavePlan covering(List<LeavePlan> list, LocalDate day) {
        return list.stream().filter(p -> p.covers(day)).findFirst().orElse(null);
    }

    /** The brand's plan before this one, for what carries forward. */
    static LeavePlan before(List<LeavePlan> list, LeavePlan plan) {
        LeavePlan found = null;
        for (LeavePlan p : list) {
            if (p.getValidUntil().isBefore(plan.getValidFrom())) {
                found = p;
            }
        }
        return found;
    }

    static Long brandIdOf(LeavePlan p) {
        return p.getBrand() == null ? null : p.getBrand().getId();
    }

    LocalDate today() {
        return LocalDate.now(zone);
    }

    // ---- the screen ----

    @Transactional(readOnly = true)
    public Overview overview(AuthUser me) {
        LocalDate today = today();
        List<LeavePlan> all = plans.allFull();
        List<BrandPlans> rows = new ArrayList<>();
        for (Brand b : scope(me)) {
            rows.add(row(b, all, today));
        }
        if (seesAll(me)) {
            rows.add(row(null, all, today));
        }
        return new Overview(today, rows);
    }

    /** Plans ending within 10 days with no next one; brands with no plan in force or waiting. */
    @Transactional(readOnly = true)
    public List<Alert> alerts(AuthUser me) {
        return alertsIn(scope(me), today());
    }

    List<Alert> alertsIn(List<Brand> list, LocalDate today) {
        List<LeavePlan> all = plans.allFull();
        List<Alert> out = new ArrayList<>();
        for (Brand b : list) {
            if (!b.isActive()) {
                continue;
            }
            List<LeavePlan> own = all.stream().filter(p -> b.getId().equals(brandIdOf(p))).toList();
            LeavePlan current = covering(own, today);
            boolean next = own.stream().anyMatch(p -> p.getValidFrom().isAfter(today));
            if (current == null && !next) {
                out.add(new Alert(b.getId(), b.getCode(), b.getName(), null, null));
            } else if (current != null && !next && daysLeft(current, today) <= NOTICE_DAYS) {
                out.add(new Alert(b.getId(), b.getCode(), b.getName(), current.getValidUntil(), daysLeft(current, today)));
            }
        }
        return out;
    }

    @Transactional
    public PlanView create(AuthUser me, PlanBody body) {
        Brand brand = brandFor(me, body.getBrandId());
        LocalDate today = today();
        List<LeavePlan> own = ofBrand(body.getBrandId());
        if (own.stream().anyMatch(p -> p.getValidFrom().isAfter(today))) {
            throw ApiException.conflict("A next plan is already waiting for this brand; change that one");
        }
        LeavePlan current = covering(own, today);
        if (current != null) {
            LocalDate opens = opensOn(current);
            if (today.isBefore(opens)) {
                throw ApiException.conflict("The next plan can be added from " + opens + ", " + NOTICE_DAYS
                        + " days before the current one ends");
            }
            if (!body.getValidFrom().isAfter(current.getValidUntil())) {
                throw ApiException.badRequest("The next plan must start after the current one ends (" + current.getValidUntil() + ")");
            }
        }
        LeavePlan plan = new LeavePlan();
        plan.setBrand(brand);
        apply(plan, body, own, today);
        return view(plans.save(plan), today);
    }

    /** The current or waiting plan; one that has ended stays as it was. */
    @Transactional
    public PlanView update(AuthUser me, Long id, PlanBody body) {
        LeavePlan plan = plan(id);
        brandFor(me, brandIdOf(plan));
        LocalDate today = today();
        if (plan.getValidUntil().isBefore(today)) {
            throw ApiException.conflict("This plan has ended and can no longer change");
        }
        checkNoLeaveOutside(plan, body);
        if (!body.getValidUntil().equals(plan.getValidUntil())) {
            plan.setExpiryNotifiedAt(null);
        }
        apply(plan, body, ofBrand(brandIdOf(plan)).stream().filter(p -> !p.getId().equals(id)).toList(), today);
        return view(plan, today);
    }

    /** Only a plan that has not started. */
    @Transactional
    public void delete(AuthUser me, Long id) {
        LeavePlan plan = plan(id);
        brandFor(me, brandIdOf(plan));
        if (!plan.getValidFrom().isAfter(today())) {
            throw ApiException.conflict("Only a plan that has not started can be removed");
        }
        plans.delete(plan);
    }

    // ---- rules ----

    private void apply(LeavePlan plan, PlanBody body, List<LeavePlan> others, LocalDate today) {
        LocalDate from = body.getValidFrom();
        LocalDate until = body.getValidUntil();
        if (until.isBefore(from)) {
            throw ApiException.badRequest("Valid until must be on or after valid from");
        }
        if (until.isBefore(today)) {
            throw ApiException.badRequest("Valid until cannot be in the past");
        }
        if (ChronoUnit.DAYS.between(from, until) > MAX_SPAN_DAYS) {
            throw ApiException.badRequest("A plan can run for at most 3 years");
        }
        for (LeavePlan p : others) {
            if (!from.isAfter(p.getValidUntil()) && !until.isBefore(p.getValidFrom())) {
                throw ApiException.conflict("These dates overlap the plan from " + p.getValidFrom() + " to " + p.getValidUntil());
            }
        }
        plan.setValidFrom(from);
        plan.setValidUntil(until);

        // kept lines stay, so a type taken off and back keeps its row
        Map<Long, LeavePlanLine> existing = plan.getLines().stream().collect(Collectors.toMap(l -> l.getLeaveType().getId(), l -> l));
        Set<Long> seen = new HashSet<>();
        List<LeavePlanLine> next = new ArrayList<>();
        for (LineBody b : body.getLines()) {
            if (!seen.add(b.getLeaveTypeId())) {
                throw ApiException.badRequest("A type of leave is listed twice");
            }
            LeaveType type = types.findById(b.getLeaveTypeId())
                    .orElseThrow(() -> ApiException.badRequest("Leave type not found: " + b.getLeaveTypeId()));
            boolean annual = type.getAllocation() == LeaveAllocation.ANNUAL;
            if (annual && b.getDays() == null) {
                throw ApiException.badRequest("Set the days for " + type.getName());
            }
            LeavePlanLine line = existing.get(type.getId());
            if (line == null) {
                line = new LeavePlanLine();
                line.setPlan(plan);
                line.setLeaveType(type);
            }
            boolean carries = b.isCarryForward() && type.getAllocation() != LeaveAllocation.UNLIMITED;
            line.setDays(annual ? b.getDays() : null);
            line.setCarryForward(carries);
            line.setMaxCarryDays(carries ? b.getMaxCarryDays() : null);
            next.add(line);
        }
        plan.getLines().removeIf(l -> !seen.contains(l.getLeaveType().getId()));
        for (LeavePlanLine l : next) {
            if (l.getId() == null) {
                plan.getLines().add(l);
            }
        }
    }

    /** New dates may not leave applied-for leave outside any plan. */
    private void checkNoLeaveOutside(LeavePlan plan, PlanBody body) {
        Long brandId = brandIdOf(plan);
        boolean stranded = requests.search(null, brandId).stream()
                .filter(r -> ACTIVE.contains(r.getStatus()))
                .filter(r -> brandId != null || r.getEmployee().getBrand() == null)
                .filter(r -> plan.covers(r.getFromDate()))
                .anyMatch(r -> r.getFromDate().isBefore(body.getValidFrom()) || r.getFromDate().isAfter(body.getValidUntil()));
        if (stranded) {
            throw ApiException.conflict("Leave has been applied for on dates these new dates leave out");
        }
    }

    private BrandPlans row(Brand b, List<LeavePlan> all, LocalDate today) {
        Long brandId = b == null ? null : b.getId();
        List<LeavePlan> own = all.stream().filter(p -> Objects.equals(brandIdOf(p), brandId)).toList();
        LeavePlan current = covering(own, today);
        boolean waiting = own.stream().anyMatch(p -> p.getValidFrom().isAfter(today));
        LocalDate opens = current == null ? null : opensOn(current);
        boolean canAdd = !waiting && (opens == null || !today.isBefore(opens));
        return new BrandPlans(brandId, b == null ? null : b.getCode(), b == null ? "Group (no brand)" : b.getName(), canAdd,
                waiting ? null : opens, current == null ? today : current.getValidUntil().plusDays(1),
                own.stream().sorted(Comparator.comparing(LeavePlan::getValidFrom).reversed()).map(p -> view(p, today)).toList());
    }

    private static LocalDate opensOn(LeavePlan current) {
        return current.getValidUntil().minusDays(NOTICE_DAYS);
    }

    private static long daysLeft(LeavePlan p, LocalDate today) {
        return ChronoUnit.DAYS.between(today, p.getValidUntil());
    }

    private static PlanView view(LeavePlan p, LocalDate today) {
        boolean ended = p.getValidUntil().isBefore(today);
        boolean future = p.getValidFrom().isAfter(today);
        List<LineView> lines = p.getLines().stream()
                .map(l -> new LineView(l.getLeaveType().getId(), l.getLeaveType().getCode(), l.getLeaveType().getName(),
                        l.getLeaveType().getAllocation(), l.getDays(), l.isCarryForward(), l.getMaxCarryDays()))
                .toList();
        return new PlanView(p.getId(), brandIdOf(p), p.getBrand() == null ? null : p.getBrand().getCode(),
                p.getBrand() == null ? "Group (no brand)" : p.getBrand().getName(), p.getValidFrom(), p.getValidUntil(),
                ended ? "ENDED" : future ? "FUTURE" : "CURRENT", ended || future ? null : daysLeft(p, today), !ended, future, lines);
    }

    /** The brand, when I may change its plans; brandId null = the group-level people. */
    private Brand brandFor(AuthUser me, Long brandId) {
        if (brandId == null) {
            if (!seesAll(me)) {
                throw ApiException.forbidden("You can only change your own brand's leave plans");
            }
            return null;
        }
        if (!seesAll(me) && !brandId.equals(me.brandId())) {
            throw ApiException.forbidden("You can only change your own brand's leave plans");
        }
        return brands.findById(brandId).orElseThrow(() -> ApiException.badRequest("Brand not found: " + brandId));
    }

    private List<Brand> scope(AuthUser me) {
        if (seesAll(me)) {
            return brands.findAllByOrderByNameAsc();
        }
        return brands.findById(me.brandId()).map(List::of).orElse(List.of());
    }

    private static boolean seesAll(AuthUser me) {
        return me.isOwner() || me.brandId() == null;
    }

    private LeavePlan plan(Long id) {
        return plans.allFull().stream().filter(p -> p.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Leave plan not found: " + id));
    }
}
