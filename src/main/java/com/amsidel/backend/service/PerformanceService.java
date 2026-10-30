/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.PerformanceRequests;
import com.amsidel.backend.dto.PerformanceViews.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static com.amsidel.backend.dto.PerformanceViews.one;

/**
 * Yearly performance: review cycles (date to date); in each, a person's self intro and skills (validated by their
 * manager), goals with weighted sub-tasks set by their manager, self and manager ratings (1-5), and achievements.
 * A goal is General (progress moved by hand, rated by the person) or Sales (a target amount; the person records what
 * they achieved, and the progress and self rating follow achieved / target).
 * "Manager" is anyone above the person in the reporting line, or an owner/admin in scope (TeamAccess). A closed
 * cycle is read-only.
 */
@Service
public class PerformanceService {

    private final ReviewCycleRepository cycleRepository;
    private final ReviewRepository reviewRepository;
    private final SkillRepository skillRepository;
    private final GoalRepository goalRepository;
    private final AchievementRepository achievementRepository;
    private final EmployeeRepository employeeRepository;
    private final BrandRepository brandRepository;
    private final TeamAccess access;
    private final MenuAccessService menuAccess;
    private final ZoneId zone;

    public PerformanceService(ReviewCycleRepository cycleRepository, ReviewRepository reviewRepository,
                              SkillRepository skillRepository, GoalRepository goalRepository,
                              AchievementRepository achievementRepository, EmployeeRepository employeeRepository,
                              BrandRepository brandRepository, TeamAccess access, MenuAccessService menuAccess,
                              @Value("${app.leave.zone}") ZoneId zone) {
        this.cycleRepository = cycleRepository;
        this.reviewRepository = reviewRepository;
        this.skillRepository = skillRepository;
        this.goalRepository = goalRepository;
        this.achievementRepository = achievementRepository;
        this.employeeRepository = employeeRepository;
        this.brandRepository = brandRepository;
        this.access = access;
        this.menuAccess = menuAccess;
        this.zone = zone;
    }

    // ---- cycles ----

    /** Cycles for my brand (or every brand), newest first; the Review cycles screen sees all of them. */
    @Transactional(readOnly = true)
    public List<CycleView> cycles(AuthUser me) {
        boolean all = menuAccess.can(me, Menus.PERF_CYCLES) || me.brandId() == null;
        return cycleRepository.newestFirst().stream()
                .filter(c -> all || c.getBrand() == null || c.getBrand().getId().equals(me.brandId()))
                .map(CycleView::of)
                .toList();
    }

    @Transactional
    public CycleView saveCycle(Long id, PerformanceRequests.Cycle body) {
        if (body.getEndDate().isBefore(body.getStartDate())) {
            throw ApiException.badRequest("The cycle must end after it starts");
        }
        ReviewCycle c = id == null ? new ReviewCycle() : cycle(id);
        c.setName(body.getName().strip());
        c.setStartDate(body.getStartDate());
        c.setEndDate(body.getEndDate());
        c.setBrand(body.getBrandId() == null ? null : brandRepository.findById(body.getBrandId())
                .orElseThrow(() -> ApiException.badRequest("Brand not found: " + body.getBrandId())));
        c.setClosed(body.isClosed());
        return CycleView.of(cycleRepository.save(c));
    }

    // ---- one person's review ----

    /** My review, or a team member's (employeeId), in a cycle. */
    @Transactional(readOnly = true)
    public ReviewView review(AuthUser me, Long cycleId, Long employeeId) {
        ReviewCycle cycle = cycle(cycleId);
        Employee person = person(employeeId == null ? me.id() : employeeId);
        Rights rights = rights(me, person, cycle);
        Optional<Review> review = reviewRepository.find(cycle.getId(), person.getId());
        LocalDate today = today();
        List<SkillView> skills = review.map(r -> skillRepository.forReview(r.getId()).stream().map(SkillView::of).toList())
                .orElse(List.of());
        List<Goal> goals = review.map(r -> goalRepository.forReview(r.getId())).orElse(List.of());
        List<GoalView> goalViews = goals.stream().map(g -> view(g, rights, today)).toList();
        List<AchievementView> achievements = achievementRepository
                .forEmployee(person.getId(), cycle.getStartDate(), cycle.getEndDate()).stream().map(AchievementView::of).toList();
        return new ReviewView(CycleView.of(cycle), Person.of(person), rights, review.map(Review::getSelfIntro).orElse(null),
                skills, goalViews, achievements, totals(goals), today,
                person.getBrand() == null ? "₹" : person.getBrand().getCurrency().symbol());
    }

    /** Everyone below me (or in my scope) with their totals in a cycle. */
    @Transactional(readOnly = true)
    public List<TeamRow> team(AuthUser me, Long cycleId) {
        ReviewCycle cycle = cycle(cycleId);
        List<Employee> people = access.team(me).stream().filter(e -> applies(cycle, e)).toList();
        if (people.isEmpty()) {
            return List.of();
        }
        Map<Long, Review> reviews = reviewRepository.forPeople(cycle.getId(), people.stream().map(Employee::getId).toList())
                .stream().collect(Collectors.toMap(r -> r.getEmployee().getId(), r -> r));
        Map<Long, List<Goal>> goalsByReview = reviews.isEmpty() ? Map.of()
                : goalRepository.forReviews(reviews.values().stream().map(Review::getId).toList()).stream()
                .collect(Collectors.groupingBy(g -> g.getReview().getId()));
        List<TeamRow> rows = new ArrayList<>();
        for (Employee e : people) {
            Review r = reviews.get(e.getId());
            List<Goal> goals = r == null ? List.of() : goalsByReview.getOrDefault(r.getId(), List.of());
            List<Skill> skills = r == null ? List.of() : skillRepository.forReview(r.getId());
            int toRate = (int) goals.stream().filter(g -> g.getSelfRating() != null && g.getManagerRating() == null).count();
            int toValidate = (int) skills.stream().filter(s -> s.getManagerLevel() == null).count();
            rows.add(new TeamRow(Person.of(e), totals(goals), skills.size(), toValidate, toRate));
        }
        return rows;
    }

    @Transactional
    public ReviewView saveIntro(AuthUser me, Long cycleId, String intro) {
        Review review = ownReview(me, cycleId);
        review.setSelfIntro(intro == null || intro.isBlank() ? null : intro.strip());
        review.setUpdatedAt(Instant.now());
        return review(me, cycleId, null);
    }

    // ---- skills ----

    @Transactional
    public SkillView addSkill(AuthUser me, Long cycleId, PerformanceRequests.SkillBody body) {
        Skill s = new Skill();
        s.setReview(ownReview(me, cycleId));
        apply(s, body);
        return SkillView.of(skillRepository.save(s));
    }

    /** A manager adds a skill to someone below them, with their own level and note (the person adds theirs later). */
    @Transactional
    public SkillView addSkillFor(AuthUser me, Long cycleId, Long employeeId, PerformanceRequests.ManagerSkill body) {
        ReviewCycle cycle = cycle(cycleId);
        Employee person = person(employeeId);
        requireManager(me, person, cycle);
        Skill s = new Skill();
        s.setReview(reviewRepository.find(cycle.getId(), person.getId()).orElseGet(() -> newReview(cycle, person)));
        s.setName(body.getName().strip());
        s.setCategory(body.getCategory());
        s.setSelfLevel(BigDecimal.ZERO);
        Employee manager = person(me.id());
        s.setAddedBy(manager);
        s.setManagerLevel(stars(body.getManagerLevel()));
        s.setManagerNote(blankToNull(body.getNote()));
        s.setValidatedBy(manager);
        s.setValidatedAt(Instant.now());
        return SkillView.of(skillRepository.save(s));
    }

    /** The person sets their level (also on a skill their manager added); renaming it asks for validation again. */
    @Transactional
    public SkillView updateSkill(AuthUser me, Long id, PerformanceRequests.SkillBody body) {
        Skill s = ownSkill(me, id);
        boolean renamed = !s.getName().equals(body.getName().strip()) || s.getCategory() != body.getCategory();
        apply(s, body);
        if (renamed) {
            // the manager validates the skill as it is now
            s.setManagerLevel(null);
            s.setManagerNote(null);
            s.setValidatedBy(null);
            s.setValidatedAt(null);
        }
        return SkillView.of(s);
    }

    /** The person removes skills they listed; a manager removes skills a manager added. */
    @Transactional
    public void deleteSkill(AuthUser me, Long id) {
        Skill s = skillRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Skill not found: " + id));
        Employee person = s.getReview().getEmployee();
        if (person.getId().equals(me.id())) {
            requireOpen(s.getReview().getCycle());
            if (s.getAddedBy() != null) {
                throw ApiException.forbidden("Your manager added this skill; ask them to remove it");
            }
        } else {
            requireManager(me, person, s.getReview().getCycle());
            if (s.getAddedBy() == null) {
                throw ApiException.forbidden(person.getName() + " listed this skill; only they can remove it");
            }
        }
        skillRepository.delete(s);
    }

    @Transactional
    public SkillView validateSkill(AuthUser me, Long id, PerformanceRequests.SkillValidation body) {
        Skill s = skillRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Skill not found: " + id));
        requireManager(me, s.getReview().getEmployee(), s.getReview().getCycle());
        s.setManagerLevel(stars(body.getManagerLevel()));
        s.setManagerNote(blankToNull(body.getNote()));
        s.setValidatedBy(employeeRepository.getReferenceById(me.id()));
        s.setValidatedAt(Instant.now());
        return SkillView.of(skillRepository.save(s));
    }

    // ---- goals (set by the manager) ----

    @Transactional
    public GoalView addGoal(AuthUser me, Long cycleId, Long employeeId, PerformanceRequests.GoalBody body) {
        ReviewCycle cycle = cycle(cycleId);
        Employee person = person(employeeId);
        requireManager(me, person, cycle);
        Review review = reviewRepository.find(cycle.getId(), person.getId()).orElseGet(() -> newReview(cycle, person));
        Goal g = new Goal();
        g.setReview(review);
        g.setCreatedBy(employeeRepository.getReferenceById(me.id()));
        apply(g, body, cycle);
        return view(goalRepository.save(g), new Rights(false, true), today());
    }

    @Transactional
    public GoalView updateGoal(AuthUser me, Long id, PerformanceRequests.GoalBody body) {
        Goal g = goal(id);
        ReviewCycle cycle = g.getReview().getCycle();
        boolean admin = isAdminOver(me, g.getReview().getEmployee());
        if (!admin) {
            requireManager(me, g.getReview().getEmployee(), cycle);
            if (g.getManagerRating() != null) {
                throw ApiException.conflict("This goal is already rated and can no longer change");
            }
        }
        // ratings, comments and progress stay as they are
        apply(g, body, cycle);
        return view(g, new Rights(false, !cycle.isClosed(), admin), today());
    }

    @Transactional
    public void deleteGoal(AuthUser me, Long id) {
        Goal g = goal(id);
        requireManager(me, g.getReview().getEmployee(), g.getReview().getCycle());
        if (!g.getSalesEntries().isEmpty()) {
            throw ApiException.conflict("Achievements are recorded against this goal; it cannot be removed");
        }
        if (g.getSelfRating() != null) {
            throw ApiException.conflict("This goal is already rated and cannot be removed");
        }
        goalRepository.delete(g);
    }

    /** % done of a sub-task: the person or their manager. */
    @Transactional
    public GoalView taskProgress(AuthUser me, Long goalId, Long taskId, int progress) {
        Goal g = goal(goalId);
        Rights rights = requireSelfOrManager(me, g);
        GoalTask t = g.getTasks().stream().filter(x -> x.getId().equals(taskId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Sub-task not found: " + taskId));
        requireGeneral(g);
        requireNotRated(g);
        t.setProgress(progress);
        return view(g, rights, today());
    }

    /** % done of a goal without sub-tasks. */
    @Transactional
    public GoalView goalProgress(AuthUser me, Long goalId, int progress) {
        Goal g = goal(goalId);
        Rights rights = requireSelfOrManager(me, g);
        requireGeneral(g);
        if (!g.getTasks().isEmpty()) {
            throw ApiException.badRequest("This goal's progress comes from its sub-tasks");
        }
        requireNotRated(g);
        g.setProgress(progress);
        return view(g, rights, today());
    }

    /** The person rates their goal once its end date has come; they can change it until the manager rates. */
    @Transactional
    public GoalView selfRate(AuthUser me, Long goalId, PerformanceRequests.Rating body) {
        Goal g = goal(goalId);
        Review review = g.getReview();
        if (!review.getEmployee().getId().equals(me.id())) {
            throw ApiException.forbidden("Only the person can rate their own goal");
        }
        if (g.isSales()) {
            throw ApiException.badRequest("A sales goal's self rating is worked out from the amounts you record");
        }
        requireOpen(review.getCycle());
        if (today().isBefore(g.getEndDate())) {
            throw ApiException.badRequest("You can rate this goal from " + g.getEndDate() + ", its end date");
        }
        if (g.getManagerRating() != null) {
            throw ApiException.conflict("Your manager has already rated this goal");
        }
        g.setSelfRating(stars(body.getRating()));
        g.setSelfComment(blankToNull(body.getComment()));
        g.setSelfRatedAt(Instant.now());
        return view(g, new Rights(true, false), today());
    }

    /** The manager rates after the person, with written feedback. */
    @Transactional
    public GoalView managerRate(AuthUser me, Long goalId, PerformanceRequests.Rating body) {
        Goal g = goal(goalId);
        requireManager(me, g.getReview().getEmployee(), g.getReview().getCycle());
        if (!selfDone(g, today())) {
            throw ApiException.badRequest(g.isSales() ? "The person records what they achieved first (or wait for the goal's end date)"
                    : "The person rates the goal first");
        }
        if (body.getComment() == null || body.getComment().isBlank()) {
            throw ApiException.badRequest("Write your feedback with the rating");
        }
        g.setManagerRating(stars(body.getRating()));
        g.setManagerComment(body.getComment().strip());
        g.setManagerRatedBy(employeeRepository.getReferenceById(me.id()));
        g.setManagerRatedAt(Instant.now());
        return view(g, new Rights(false, true), today());
    }

    // ---- sales goals: what the person achieved ----

    @Transactional
    public GoalView addSalesEntry(AuthUser me, Long goalId, PerformanceRequests.SalesEntryBody body) {
        Goal g = goal(goalId);
        requireRecorder(me, g);
        SalesEntry e = new SalesEntry();
        e.setGoal(g);
        e.setCreatedAt(Instant.now());
        apply(e, g, body);
        g.getSalesEntries().add(e);
        rateSales(g);
        goalRepository.saveAndFlush(g);
        return view(g, new Rights(true, false), today());
    }

    @Transactional
    public GoalView updateSalesEntry(AuthUser me, Long id, PerformanceRequests.SalesEntryBody body) {
        Goal g = goalOfEntry(id);
        requireRecorder(me, g);
        SalesEntry e = g.getSalesEntries().stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        apply(e, g, body);
        e.setUpdatedAt(Instant.now());
        rateSales(g);
        return view(g, new Rights(true, false), today());
    }

    @Transactional
    public GoalView deleteSalesEntry(AuthUser me, Long id) {
        Goal g = goalOfEntry(id);
        requireRecorder(me, g);
        g.getSalesEntries().removeIf(x -> x.getId().equals(id));
        rateSales(g);
        return view(g, new Rights(true, false), today());
    }

    private Goal goalOfEntry(Long entryId) {
        Long goalId = goalRepository.goalOfSalesEntry(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Achievement record not found: " + entryId));
        return goal(goalId);
    }

    /** Only the person records against their sales goal, while the cycle is open and before the manager rates it. */
    private void requireRecorder(AuthUser me, Goal g) {
        if (!g.isSales()) {
            throw ApiException.badRequest("Amounts are recorded only against sales goals");
        }
        if (!g.getReview().getEmployee().getId().equals(me.id())) {
            throw ApiException.forbidden("Only the person records what they achieved");
        }
        requireOpen(g.getReview().getCycle());
        if (g.getManagerRating() != null) {
            throw ApiException.conflict("Your manager has already rated this goal");
        }
    }

    /** Inside the goal's (or sub-task's) dates; a goal with sub-tasks needs one; invoice numbers tidied up. */
    private static void apply(SalesEntry e, Goal g, PerformanceRequests.SalesEntryBody body) {
        if (body.getToDate().isBefore(body.getFromDate())) {
            throw ApiException.badRequest("The end date must be on or after the start date");
        }
        GoalTask task = null;
        if (!g.getTasks().isEmpty()) {
            if (body.getTaskId() == null) {
                throw ApiException.badRequest("Choose the sub-task it counts for");
            }
            task = g.getTasks().stream().filter(t -> t.getId().equals(body.getTaskId())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("That sub-task is not part of this goal"));
        }
        java.time.LocalDate from = task == null ? g.getStartDate() : task.getStartDate();
        java.time.LocalDate to = task == null ? g.getEndDate() : task.getEndDate();
        if (body.getFromDate().isBefore(from) || body.getToDate().isAfter(to)) {
            throw ApiException.badRequest("The dates must be inside " + (task == null ? "the goal's" : "the sub-task's") + " dates ("
                    + from + " to " + to + ")");
        }
        e.setTask(task);
        e.setAmount(body.getAmount().setScale(2, java.math.RoundingMode.HALF_UP));
        e.setFromDate(body.getFromDate());
        e.setToDate(body.getToDate());
        e.setInvoiceNumbers(invoices(body.getInvoiceNumbers()));
        e.setDescription(blankToNull(body.getDescription()));
    }

    /** "inv-1,  INV-2 ,inv-1" -> "inv-1, INV-2": trimmed, empty ones and repeats left out. */
    static String invoices(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LinkedHashSet<String> list = new LinkedHashSet<>();
        for (String part : raw.split("[,;\\n]")) {
            String v = part.strip();
            if (!v.isEmpty()) {
                list.add(v);
            }
        }
        String joined = String.join(", ", list);
        if (joined.length() > 1000) {
            throw ApiException.badRequest("Too many invoice numbers for one record; split it into two");
        }
        return joined.isEmpty() ? null : joined;
    }

    /** A sales goal's self rating: achieved / target as stars (5 = target met), in half stars; none until something is recorded. */
    private void rateSales(Goal g) {
        if (g.getSalesEntries().isEmpty()) {
            g.setSelfRating(null);
            g.setSelfRatedAt(null);
            return;
        }
        double ratio = ratio(achieved(g), g.getTargetAmount());
        double stars = Math.max(0.5, Math.min(5, Math.round(Math.min(ratio, 1) * 10) / 2.0));
        g.setSelfRating(BigDecimal.valueOf(stars).setScale(1, java.math.RoundingMode.HALF_UP));
        g.setSelfRatedAt(Instant.now());
    }

    private static BigDecimal achieved(Goal g) {
        return g.getSalesEntries().stream().map(SalesEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal achieved(Goal g, GoalTask t) {
        return g.getSalesEntries().stream().filter(e -> e.getTask() != null && e.getTask().getId().equals(t.getId()))
                .map(SalesEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static double ratio(BigDecimal achieved, BigDecimal target) {
        return target == null || target.signum() <= 0 ? 0 : achieved.doubleValue() / target.doubleValue();
    }

    /** The person's part is done: rated (general), or something recorded or the end date come (sales). */
    private static boolean selfDone(Goal g, LocalDate today) {
        return g.getSelfRating() != null || (g.isSales() && !today.isBefore(g.getEndDate()));
    }

    private static void requireGeneral(Goal g) {
        if (g.isSales()) {
            throw ApiException.badRequest("A sales goal's progress comes from the amounts recorded");
        }
    }

    // ---- achievements ----

    /** Given by a manager to someone below them; people do not add their own (older self-reported ones stay). */
    @Transactional
    public AchievementView addAchievement(AuthUser me, PerformanceRequests.AchievementBody body) {
        boolean self = body.getEmployeeId() == null || body.getEmployeeId().equals(me.id());
        if (self) {
            throw ApiException.forbidden("Achievements are given by your manager; you cannot add your own");
        }
        Employee person = person(body.getEmployeeId());
        if (!menuAccess.can(me, Menus.PERF_TEAM) || !access.canView(me, person)) {
            throw ApiException.forbidden("You can only give achievements to people below you");
        }
        Achievement a = new Achievement();
        a.setEmployee(person);
        a.setTitle(body.getTitle().strip());
        a.setCategory(blankToNull(body.getCategory()));
        a.setAchievedOn(body.getAchievedOn());
        a.setDetails(blankToNull(body.getDetails()));
        a.setIssuedBy(self ? null : employeeRepository.getReferenceById(me.id()));
        return AchievementView.of(achievementRepository.save(a));
    }

    /** Self-reported: the person; issued: whoever issued it, or someone above the person. */
    @Transactional
    public void deleteAchievement(AuthUser me, Long id) {
        Achievement a = achievementRepository.findFull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Achievement not found: " + id));
        boolean mine = a.getEmployee().getId().equals(me.id());
        boolean allowed = a.getIssuedBy() == null ? mine
                : a.getIssuedBy().getId().equals(me.id()) || (!mine && access.canView(me, a.getEmployee()));
        if (!allowed) {
            throw ApiException.forbidden(mine ? "An issued achievement can only be removed by your manager"
                    : "You cannot remove this achievement");
        }
        achievementRepository.delete(a);
    }

    // ---- rules and helpers ----

    private Rights rights(AuthUser me, Employee person, ReviewCycle cycle) {
        boolean self = me.id().equals(person.getId());
        if (!self && !access.canView(me, person)) {
            throw ApiException.forbidden("You can only see your own review and those of people below you");
        }
        boolean open = !cycle.isClosed();
        boolean manager = !self && menuAccess.can(me, Menus.PERF_TEAM);
        return new Rights(self && open, manager && open, isAdminOver(me, person));
    }

    /** An owner, or an admin over this person's brand, may edit any of their goals. Never their own. */
    private boolean isAdminOver(AuthUser me, Employee person) {
        return !me.id().equals(person.getId()) && (me.isOwner() || me.role() == Role.ADMIN) && access.canView(me, person);
    }

    private void requireManager(AuthUser me, Employee person, ReviewCycle cycle) {
        if (me.id().equals(person.getId())) {
            throw ApiException.forbidden("Your manager does this for you");
        }
        if (!menuAccess.can(me, Menus.PERF_TEAM) || !access.canView(me, person)) {
            throw ApiException.forbidden("You can only do this for people below you");
        }
        requireOpen(cycle);
    }

    private Rights requireSelfOrManager(AuthUser me, Goal g) {
        Employee person = g.getReview().getEmployee();
        requireOpen(g.getReview().getCycle());
        if (me.id().equals(person.getId())) {
            return new Rights(true, false);
        }
        requireManager(me, person, g.getReview().getCycle());
        return new Rights(false, true);
    }

    private static void requireOpen(ReviewCycle cycle) {
        if (cycle.isClosed()) {
            throw ApiException.conflict("The review cycle " + cycle.getName() + " is closed");
        }
    }

    private static void requireNotRated(Goal g) {
        if (g.getManagerRating() != null) {
            throw ApiException.conflict("This goal is already rated and can no longer change");
        }
    }

    private Review ownReview(AuthUser me, Long cycleId) {
        ReviewCycle cycle = cycle(cycleId);
        requireOpen(cycle);
        Employee person = person(me.id());
        if (!applies(cycle, person)) {
            throw ApiException.forbidden("This review cycle is for another brand");
        }
        return reviewRepository.find(cycle.getId(), person.getId()).orElseGet(() -> newReview(cycle, person));
    }

    private Review newReview(ReviewCycle cycle, Employee person) {
        Review r = new Review();
        r.setCycle(cycle);
        r.setEmployee(person);
        r.setUpdatedAt(Instant.now());
        return reviewRepository.save(r);
    }

    private Skill ownSkill(AuthUser me, Long id) {
        Skill s = skillRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Skill not found: " + id));
        if (!s.getReview().getEmployee().getId().equals(me.id())) {
            throw ApiException.forbidden("You can only change your own skills");
        }
        requireOpen(s.getReview().getCycle());
        return s;
    }

    private static void apply(Skill s, PerformanceRequests.SkillBody body) {
        s.setName(body.getName().strip());
        s.setCategory(body.getCategory());
        s.setSelfLevel(stars(body.getSelfLevel()));
    }

    /** Goal weights add up to at most 100 per review; sub-task weights to exactly 100 per goal; dates inside the cycle. */
    private void apply(Goal g, PerformanceRequests.GoalBody body, ReviewCycle cycle) {
        if (body.getEndDate().isBefore(body.getStartDate())) {
            throw ApiException.badRequest("The goal must end after it starts");
        }
        if (body.getStartDate().isBefore(cycle.getStartDate()) || body.getEndDate().isAfter(cycle.getEndDate())) {
            throw ApiException.badRequest("Goal dates must be inside the cycle (" + cycle.getStartDate() + " to "
                    + cycle.getEndDate() + ")");
        }
        int others = g.getReview().getId() == null ? 0 : goalRepository.weightOf(g.getReview().getId(), g.getId());
        if (others + body.getWeightage() > 100) {
            throw ApiException.badRequest("Goals would add up to " + (others + body.getWeightage())
                    + "%; this person has " + (100 - others) + "% left");
        }
        List<PerformanceRequests.TaskBody> tasks = body.getTasks() == null ? List.of() : body.getTasks();
        GoalType type = body.getType() == null ? GoalType.GENERAL : body.getType();
        boolean sales = type == GoalType.SALES;
        if (g.getId() != null && type != (g.isSales() ? GoalType.SALES : GoalType.GENERAL) && !g.getSalesEntries().isEmpty()) {
            throw ApiException.conflict("Achievements are recorded against this goal; it stays a sales goal");
        }
        // sales: a target amount, split over the sub-tasks (their weights follow their targets)
        int[] weights = new int[tasks.size()];
        if (sales) {
            if (body.getTargetAmount() == null) {
                throw ApiException.badRequest("Give the goal's target amount");
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (PerformanceRequests.TaskBody t : tasks) {
                if (t.getTargetAmount() == null) {
                    throw ApiException.badRequest("Give sub-task \"" + t.getTitle().strip() + "\" its target amount");
                }
                sum = sum.add(t.getTargetAmount());
            }
            if (!tasks.isEmpty() && sum.compareTo(body.getTargetAmount()) != 0) {
                throw ApiException.badRequest("Sub-task targets add up to " + sum.stripTrailingZeros().toPlainString()
                        + "; they must make the goal's target of " + body.getTargetAmount().stripTrailingZeros().toPlainString());
            }
            int given = 0;
            for (int i = 0; i < tasks.size(); i++) {
                weights[i] = i == tasks.size() - 1 ? 100 - given
                        : (int) Math.round(tasks.get(i).getTargetAmount().doubleValue() * 100 / body.getTargetAmount().doubleValue());
                given += weights[i];
            }
        } else {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).getWeightage() == null) {
                    throw ApiException.badRequest("Give sub-task \"" + tasks.get(i).getTitle().strip() + "\" its weightage");
                }
                weights[i] = tasks.get(i).getWeightage();
            }
            int taskTotal = java.util.Arrays.stream(weights).sum();
            if (!tasks.isEmpty() && taskTotal != 100) {
                throw ApiException.badRequest("Sub-task weights add up to " + taskTotal + "%; they must make 100%");
            }
        }
        // a sub-task with achievements recorded against it stays
        Set<Long> kept = tasks.stream().map(PerformanceRequests.TaskBody::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        for (SalesEntry e : g.getSalesEntries()) {
            if (e.getTask() != null && !kept.contains(e.getTask().getId())) {
                throw ApiException.conflict("Achievements are recorded against sub-task \"" + e.getTask().getTitle() + "\"; keep it");
            }
        }
        for (PerformanceRequests.TaskBody t : tasks) {
            if (t.getEndDate().isBefore(t.getStartDate())) {
                throw ApiException.badRequest("Sub-task \"" + t.getTitle().strip() + "\" must end after it starts");
            }
            if (t.getStartDate().isBefore(body.getStartDate()) || t.getEndDate().isAfter(body.getEndDate())) {
                throw ApiException.badRequest("Sub-task \"" + t.getTitle().strip() + "\" must be inside the goal's dates");
            }
        }

        g.setTitle(body.getTitle().strip());
        g.setType(type);
        g.setTargetAmount(sales ? body.getTargetAmount().setScale(2, java.math.RoundingMode.HALF_UP) : null);
        g.setDescription(blankToNull(body.getDescription()));
        g.setWeightage(body.getWeightage());
        g.setStartDate(body.getStartDate());
        g.setEndDate(body.getEndDate());

        // sub-tasks: kept ones keep their progress, the rest are replaced
        Map<Long, GoalTask> existing = g.getTasks().stream().filter(t -> t.getId() != null)
                .collect(Collectors.toMap(GoalTask::getId, t -> t));
        List<GoalTask> next = new ArrayList<>();
        int order = 0;
        for (PerformanceRequests.TaskBody body2 : tasks) {
            GoalTask t = body2.getId() != null && existing.containsKey(body2.getId()) ? existing.get(body2.getId()) : new GoalTask();
            t.setGoal(g);
            t.setTitle(body2.getTitle().strip());
            t.setWeightage(weights[order]);
            t.setTargetAmount(sales ? body2.getTargetAmount().setScale(2, java.math.RoundingMode.HALF_UP) : null);
            t.setStartDate(body2.getStartDate());
            t.setEndDate(body2.getEndDate());
            t.setSortOrder(order++);
            next.add(t);
        }
        g.getTasks().clear();
        g.getTasks().addAll(next);
        if (sales) {
            // a new target changes how far along the recorded amounts are
            rateSales(g);
        }
    }

    private GoalView view(Goal g, Rights rights, LocalDate today) {
        boolean ended = !today.isBefore(g.getEndDate());
        boolean sales = g.isSales();
        List<TaskView> tasks = g.getTasks().stream().map(t -> !sales ? TaskView.of(t)
                : new TaskView(t.getId(), t.getTitle(), t.getWeightage(), t.getStartDate(), t.getEndDate(),
                (int) Math.min(100, Math.round(ratio(achieved(g, t), t.getTargetAmount()) * 100)), t.getTargetAmount(), achieved(g, t))).toList();
        BigDecimal achieved = sales ? achieved(g) : null;
        return new GoalView(g.getId(), g.getTitle(), g.getDescription(), g.getWeightage(), g.getStartDate(), g.getEndDate(),
                one(progressOf(g)), tasks, g.getSelfRating(), g.getSelfComment(),
                g.getSelfRatedAt(), g.getManagerRating(), g.getManagerComment(),
                g.getManagerRatedBy() == null ? null : g.getManagerRatedBy().getName(), g.getManagerRatedAt(),
                !sales && rights.self() && ended && g.getManagerRating() == null,
                rights.manager() && selfDone(g, today),
                rights.admin() || (rights.manager() && g.getManagerRating() == null),
                sales ? GoalType.SALES : GoalType.GENERAL, g.getTargetAmount(), achieved,
                sales ? one(ratio(achieved, g.getTargetAmount()) * 100) : null,
                sales ? g.getSalesEntries().stream().map(SalesEntryView::of).toList() : List.of(),
                sales && rights.self() && g.getManagerRating() == null);
    }

    /** % done: achieved / target for a sales goal (at most 100); else weighted sub-tasks, or the goal's own %. */
    private static double progressOf(Goal g) {
        if (g.isSales()) {
            return Math.min(100, ratio(achieved(g), g.getTargetAmount()) * 100);
        }
        if (g.getTasks().isEmpty()) {
            return g.getProgress();
        }
        return g.getTasks().stream().mapToDouble(t -> t.getWeightage() * t.getProgress() / 100.0).sum();
    }

    private static Totals totals(List<Goal> goals) {
        int weight = goals.stream().mapToInt(Goal::getWeightage).sum();
        double progress = goals.stream().mapToDouble(g -> g.getWeightage() * progressOf(g) / 100.0).sum();
        return new Totals(weight, one(progress),
                weightedRating(goals, Goal::getSelfRating), weightedRating(goals, Goal::getManagerRating),
                goals.size(),
                (int) goals.stream().filter(g -> g.getSelfRating() != null).count(),
                (int) goals.stream().filter(g -> g.getManagerRating() != null).count());
    }

    /** Average of the rated goals, each counted by its weight; null when none is rated. */
    private static BigDecimal weightedRating(List<Goal> goals, java.util.function.Function<Goal, BigDecimal> rating) {
        double sum = 0;
        int weight = 0;
        for (Goal g : goals) {
            BigDecimal r = rating.apply(g);
            if (r != null) {
                sum += r.doubleValue() * g.getWeightage();
                weight += g.getWeightage();
            }
        }
        return weight == 0 ? null : one(sum / weight);
    }

    private static boolean applies(ReviewCycle cycle, Employee e) {
        return cycle.getBrand() == null || e.getBrand() == null || cycle.getBrand().getId().equals(e.getBrand().getId());
    }

    private ReviewCycle cycle(Long id) {
        return cycleRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Review cycle not found: " + id));
    }

    private Goal goal(Long id) {
        return goalRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Goal not found: " + id));
    }

    private Employee person(Long id) {
        return employeeRepository.findWithBrandById(id).orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    /** A rating in half stars (0.5, 1, 1.5 ... 5), kept with one decimal place. */
    static BigDecimal stars(BigDecimal value) {
        BigDecimal doubled = value.multiply(BigDecimal.valueOf(2));
        if (doubled.stripTrailingZeros().scale() > 0) {
            throw ApiException.badRequest("Ratings go in half stars: 0.5, 1, 1.5 ... 5");
        }
        return value.setScale(1, java.math.RoundingMode.UNNECESSARY);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
