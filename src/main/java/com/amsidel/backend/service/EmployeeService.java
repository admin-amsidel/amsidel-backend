/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.UserType;
import com.amsidel.backend.dto.EmployeeRequest;
import com.amsidel.backend.dto.EmployeeResponse;
import com.amsidel.backend.dto.MyDetailsRequest;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Designation;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.Role;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.DesignationRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.SignedInCache;
import com.amsidel.backend.validation.PinPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Employee records, with the group's access rules:
 * owners manage everyone; an admin manages managers and employees of their own brand
 * (every brand when the admin has none). Only owners create owners and admins.
 */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeService {

    /** Employee numbers for people without a brand (owners, group admins). */
    private static final String GROUP_PREFIX = "GRP";

    private final EmployeeRepository employeeRepository;
    private final BrandRepository brandRepository;
    private final DesignationRepository designationRepository;
    private final PasswordEncoder passwordEncoder;
    private final SignedInCache signedIn;
    private final UserTypeService userTypes;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final TimeZones timeZones;

    @Transactional(readOnly = true)
    public List<EmployeeResponse> list(AuthUser me, Long brandId) {
        if (!me.isOwner() && !me.isGroupAdmin()) {
            if (brandId != null && !brandId.equals(me.brandId())) {
                throw ApiException.forbidden("You can only see employees of your own brand");
            }
            brandId = me.brandId();
        }
        return employeeRepository.search(brandId).stream().map(EmployeeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse get(AuthUser me, Long id) {
        Employee employee = find(id);
        if (!inScope(me, brandIdOf(employee))) {
            throw ApiException.forbidden("You can only see employees of your own brand");
        }
        return EmployeeResponse.from(employee);
    }

    @Transactional
    public EmployeeResponse create(AuthUser me, EmployeeRequest request) {
        UserType type = userTypes.forEmployee(request.getUserTypeId(), request.getRole(), null);
        if (type != null) {
            request.setRole(type.getBaseRole());
        }
        checkCanAssign(me, request.getRole(), request.getBrandId());
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw ApiException.badRequest(PinPolicy.RULE);
        }

        Employee employee = new Employee();
        employee.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        employee.setMustChangePassword(true);
        employee.setPasswordChangedAt(Instant.now());
        apply(employee, request);
        applyAutoApprove(me, employee, request);
        employee.setUserType(type);
        EmployeeResponse saved = EmployeeResponse.from(employeeRepository.save(employee));
        teamsChanged();
        return saved;
    }

    @Transactional
    public EmployeeResponse update(AuthUser me, Long id, EmployeeRequest request) {
        Employee employee = find(id);
        checkCanManage(me, employee);
        UserType type = userTypes.forEmployee(request.getUserTypeId(), request.getRole(), employee.getUserType());
        if (type != null) {
            request.setRole(type.getBaseRole());
        }
        checkCanAssign(me, request.getRole(), request.getBrandId());
        if (employee.getId().equals(me.id())) {
            if (request.getRole() != employee.getRole()) {
                throw ApiException.badRequest("You cannot change your own role");
            }
            if (Boolean.FALSE.equals(request.getActive())) {
                throw ApiException.badRequest("You cannot disable your own account");
            }
        }
        if (employee.getId().equals(me.id()) && type != null && employee.getUserType() != null && !type.getId().equals(employee.getUserType().getId())) {
            throw ApiException.badRequest("You cannot change your own user type");
        }
        apply(employee, request);
        applyAutoApprove(me, employee, request);
        employee.setUserType(type);
        signedIn.evict(employee.getId());
        teamsChanged();
        return EmployeeResponse.from(employee);
    }

    /** Who reports to whom may have changed: managers' "leads a team" is worked out again (after the commit). */
    private void teamsChanged() {
        signedIn.evictAll();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            signedIn.evictAll();
                        }
                    });
        }
    }

    // ---- the employee generator ----

    private static final int MAX_GENERATED = 500;

    public record GenerateBody(
            @jakarta.validation.constraints.NotNull(message = "Choose the brand") Long brandId,
            @jakarta.validation.constraints.NotNull(message = "Choose the designation") Long designationId,
            @jakarta.validation.constraints.NotNull(message = "Give the number of employees")
            @jakarta.validation.constraints.Min(value = 1, message = "Give at least 1 employee")
            @jakarta.validation.constraints.Max(value = MAX_GENERATED, message = "Give at most 500 employees at a time") Integer count,
            @jakarta.validation.constraints.NotBlank(message = "Give the password") String password,
            @jakarta.validation.constraints.NotNull(message = "Say whether this is a temporary password") Boolean temporaryPassword,
            Long reportsToId) {
    }

    public record Generated(int count, String first, String last, boolean temporaryPassword, String brandCode, String designationName, String reportsToName) {
    }

    /** Someone they can report to. */
    public record Superior(Long id, String name, String empNumber, String designationName, String brandCode) {
    }

    public record NextNumber(String next) {
    }

    @Transactional(readOnly = true)
    public NextNumber nextGenerated(AuthUser me, Long brandId) {
        Brand brand = resolveBrand(brandId, new Employee());
        checkCanAssign(me, Role.EMPLOYEE, brandId);
        return new NextNumber(freeNumbers(brand, 1).getFirst());
    }

    /** Who a brand's generated employees can report to: its active people, and the group-level ones such as owners. */
    @Transactional(readOnly = true)
    public List<Superior> generatorSuperiors(AuthUser me, Long brandId) {
        resolveBrand(brandId, new Employee());
        checkCanAssign(me, Role.EMPLOYEE, brandId);
        return employeeRepository.search(null).stream().filter(e -> canLeadGenerated(e, brandId))
                .map(e -> new Superior(e.getId(), e.getName(), e.getEmpNumber(), e.getDesignation() == null ? null : e.getDesignation().getName(), e.getBrand() == null ? null : e.getBrand().getCode()))
                .sorted(java.util.Comparator.comparing(Superior::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static boolean canLeadGenerated(Employee e, Long brandId) {
        return e.isActive() && (e.getBrand() == null || e.getBrand().getId().equals(brandId));
    }

    /**
     * Test employees: plain employees of the brand with the next numbers as their employee number and name, and as their
     * user ID in small letters, all with the one password. All or nothing. The password is worked out once and first: that
     * is slow, and no connection is held meanwhile.
     */
    public Generated generate(AuthUser me, GenerateBody body) {
        if (!PinPolicy.isValid(body.password())) {
            throw ApiException.badRequest(PinPolicy.RULE);
        }
        checkCanAssign(me, Role.EMPLOYEE, body.brandId());
        String hash = passwordEncoder.encode(body.password());
        boolean temporary = body.temporaryPassword();
        Generated done = transactions.execute(status -> {
            Brand brand = resolveBrand(body.brandId(), new Employee());
            Employee check = new Employee();
            check.setBrand(brand);
            Designation designation = resolveDesignation(body.designationId(), check);
            Employee reportsTo = resolveReportsTo(body.reportsToId(), check);
            if (reportsTo != null && !canLeadGenerated(reportsTo, body.brandId())) {
                throw ApiException.badRequest(reportsTo.getName() + " cannot be chosen: choose an active person of " + brand.getName() + " or a group-level person");
            }
            UserType type = userTypes.forEmployee(null, Role.EMPLOYEE, null);
            Instant now = Instant.now();
            java.time.LocalDate today = java.time.LocalDate.now(timeZones.of(me));
            List<String> numbers = freeNumbers(brand, body.count());
            List<Employee> made = new java.util.ArrayList<>();
            for (String number : numbers) {
                Employee e = new Employee();
                e.setEmpNumber(number);
                e.setUserId(AuthService.normalizeUserId(number));
                e.setName(number);
                e.setRole(Role.EMPLOYEE);
                e.setUserType(type);
                e.setBrand(brand);
                e.setDesignation(designation);
                e.setReportsTo(reportsTo);
                e.setJoinedDate(today);
                e.setPasswordHash(hash);
                e.setMustChangePassword(temporary);
                e.setPasswordChangedAt(now);
                made.add(e);
            }
            employeeRepository.saveAll(made);
            employeeRepository.flush();
            // whoever they report to now leads a team, which opens the team menus and approvals
            teamsChanged();
            return new Generated(numbers.size(), numbers.getFirst(), numbers.getLast(), temporary, brand.getCode(), designation.getName(), reportsTo == null ? null : reportsTo.getName());
        });
        log.info("Employee generator: {} employees {} to {} made by employee {} ({} password)", done.count(), done.first(), done.last(), me.id(), temporary ? "temporary" : "permanent");
        return done;
    }

    /** The next numbers after the brand's highest, leaving out any whose number or user ID someone already has. */
    private List<String> freeNumbers(Brand brand, int count) {
        String prefix = brand.getCode().toUpperCase(Locale.ROOT);
        Set<String> takenNumbers = new HashSet<>();
        Set<String> takenUserIds = new HashSet<>();
        long highest = 0;
        for (Object[] row : employeeRepository.allNumbersAndUserIds()) {
            String number = ((String) row[0]).toUpperCase(Locale.ROOT);
            takenNumbers.add(number);
            takenUserIds.add((String) row[1]);
            String rest = number.startsWith(prefix) ? number.substring(prefix.length()) : "";
            // as a number, not as text: DW10000 comes after DW9999
            if (rest.matches("\\d{1,15}")) {
                highest = Math.max(highest, Long.parseLong(rest));
            }
        }
        List<String> free = new java.util.ArrayList<>();
        for (long next = highest + 1; free.size() < count; next++) {
            String number = String.format("%s%04d", prefix, next);
            if (!takenNumbers.contains(number) && !takenUserIds.contains(AuthService.normalizeUserId(number))) {
                free.add(number);
            }
        }
        return free;
    }

    /** Sets a temporary PIN, releases a locked account and signs the person out everywhere. */
    @Transactional
    public void resetPassword(AuthUser me, Long id, String newPassword) {
        Employee employee = find(id);
        if (employee.getId().equals(me.id())) {
            throw ApiException.badRequest("Use Change PIN for your own account");
        }
        checkCanManage(me, employee);
        employee.setPasswordHash(passwordEncoder.encode(newPassword));
        employee.setMustChangePassword(true);
        employee.setFailedAttempts(0);
        employee.setLockedUntil(null);
        if (employee.getLockedAt() != null) {
            log.info("Sign-in released for {} by {} (temporary PIN)", employee.getUserId(), me.userId());
        }
        employee.setLockedAt(null);
        employee.setPasswordChangedAt(Instant.now());
    }

    /** The employee, when {@code me} may see their record (and photo). */
    @Transactional(readOnly = true)
    public Employee findVisible(AuthUser me, Long id) {
        Employee employee = find(id);
        if (!inScope(me, brandIdOf(employee))) {
            throw ApiException.forbidden("You can only see employees of your own brand");
        }
        return employee;
    }

    /** My own personal details (My profile); everything else on the record stays with the admins. */
    @Transactional
    public EmployeeResponse updateMine(AuthUser me, MyDetailsRequest request) {
        Employee employee = find(me.id());
        employee.setPhone(Phones.normalize(request.getPhone(), "Phone"));
        employee.setPersonalEmail(lowerOrNull(request.getPersonalEmail()));
        employee.setEmergencyContact(Phones.normalize(request.getEmergencyContact(), "Emergency contact"));
        employee.setAddress(trimToNull(request.getAddress()));
        employee.setDob(request.getDob());
        employee.setBloodGroup(trimToNull(request.getBloodGroup()));
        return EmployeeResponse.from(employeeRepository.saveAndFlush(employee));
    }

    /** The employee, when {@code me} may change their record (and photo). */
    @Transactional(readOnly = true)
    public Employee findManageable(AuthUser me, Long id) {
        Employee employee = find(id);
        checkCanManage(me, employee);
        return employee;
    }

    /** Whether leave of the people reporting to this person is approved automatically: owners and admins only. */
    private static void applyAutoApprove(AuthUser me, Employee employee, EmployeeRequest request) {
        if (request.getAutoApproveReports() != null && (me.isOwner() || me.role() == Role.ADMIN)) {
            employee.setAutoApproveReports(request.getAutoApproveReports());
        }
    }

    private void apply(Employee employee, EmployeeRequest request) {
        String userId = AuthService.normalizeUserId(request.getUserId());
        if (!userId.equals(employee.getUserId()) && employeeRepository.existsByUserId(userId)) {
            throw ApiException.conflict("User ID '" + userId + "' is already taken");
        }

        Brand brand = resolveBrand(request.getBrandId(), employee);

        String empNumber = trimToNull(request.getEmpNumber());
        if (empNumber == null) {
            empNumber = employee.getEmpNumber() != null ? employee.getEmpNumber() : nextEmpNumber(brand);
        } else {
            empNumber = empNumber.toUpperCase(Locale.ROOT);
            if (!empNumber.equalsIgnoreCase(employee.getEmpNumber())
                    && employeeRepository.existsByEmpNumberIgnoreCase(empNumber)) {
                throw ApiException.conflict("Employee number " + empNumber + " is already used");
            }
        }

        employee.setUserId(userId);
        employee.setEmpNumber(empNumber);
        employee.setRole(request.getRole());
        employee.setBrand(brand);
        employee.setDesignation(resolveDesignation(request.getDesignationId(), employee));
        employee.setReportsTo(resolveReportsTo(request.getReportsToId(), employee));
        employee.setApprover(resolveApprover(request.getApproverId(), employee));
        employee.setDocumentUploadLimit(request.getDocumentUploadLimit());
        employee.setName(request.getName().trim());
        employee.setAddress(trimToNull(request.getAddress()));
        employee.setPhone(Phones.normalize(request.getPhone(), "Phone"));
        employee.setPhotoPath(trimToNull(request.getPhotoPath()));
        employee.setJoinedDate(request.getJoinedDate());
        employee.setDob(request.getDob());
        employee.setBloodGroup(trimToNull(request.getBloodGroup()));
        employee.setPersonalEmail(lowerOrNull(request.getPersonalEmail()));
        employee.setCompanyEmail(lowerOrNull(request.getCompanyEmail()));
        employee.setEmergencyContact(Phones.normalize(request.getEmergencyContact(), "Emergency contact"));
        if (request.getActive() != null) {
            employee.setActive(request.getActive());
        }
    }

    private Brand resolveBrand(Long brandId, Employee employee) {
        if (brandId == null) {
            return null;
        }
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> ApiException.badRequest("Brand not found: " + brandId));
        // an inactive brand keeps its existing people, but nobody new joins it
        boolean unchanged = Objects.equals(brandId, brandIdOf(employee));
        if (!brand.isActive() && !unchanged) {
            throw ApiException.badRequest("Brand " + brand.getName() + " is not active");
        }
        return brand;
    }

    private Designation resolveDesignation(Long designationId, Employee employee) {
        if (designationId == null) {
            return null;
        }
        Designation designation = designationRepository.findById(designationId)
                .orElseThrow(() -> ApiException.badRequest("Designation not found: " + designationId));
        boolean unchanged = employee.getDesignation() != null && designationId.equals(employee.getDesignation().getId());
        if (!designation.isActive() && !unchanged) {
            throw ApiException.badRequest("Designation " + designation.getName() + " is no longer in use");
        }
        // a brand's designation is only for its own people (the brand is set before the designation)
        Brand own = designation.getBrand();
        if (own != null && (employee.getBrand() == null || !own.getId().equals(employee.getBrand().getId()))) {
            throw ApiException.badRequest(designation.getName() + " is a designation of " + own.getName()
                    + "; choose one of " + (employee.getBrand() == null ? "the group-wide designations" : employee.getBrand().getName() + "'s"));
        }
        return designation;
    }

    /** The approver must exist and be someone else. */
    private Employee resolveApprover(Long approverId, Employee employee) {
        if (approverId == null) {
            return null;
        }
        if (approverId.equals(employee.getId())) {
            throw ApiException.badRequest("Someone cannot approve their own attendance and work log");
        }
        return employeeRepository.findWithBrandById(approverId)
                .orElseThrow(() -> ApiException.badRequest("Approver not found: " + approverId));
    }

    /** The superior must exist, and the chain above them must not lead back to this employee. */
    private Employee resolveReportsTo(Long reportsToId, Employee employee) {
        if (reportsToId == null) {
            return null;
        }
        if (reportsToId.equals(employee.getId())) {
            throw ApiException.badRequest("Someone cannot report to themselves");
        }
        Employee boss = employeeRepository.findWithBrandById(reportsToId)
                .orElseThrow(() -> ApiException.badRequest("Reports-to person not found: " + reportsToId));
        if (employee.getId() != null) {
            Set<Long> seen = new HashSet<>();
            for (Employee up = boss; up != null && seen.add(up.getId()); up = up.getReportsTo()) {
                if (up.getId().equals(employee.getId())) {
                    throw ApiException.badRequest(boss.getName() + " already reports (directly or indirectly) to "
                            + employee.getName() + ", so this would make a loop");
                }
            }
        }
        return boss;
    }

    /** Next free number for the brand, e.g. AD0001, AD0002 ... (GRP0001 for group-level people). */
    private String nextEmpNumber(Brand brand) {
        String prefix = brand == null ? GROUP_PREFIX : brand.getCode().toUpperCase(Locale.ROOT);
        int next = employeeRepository.findTopByEmpNumberStartingWithOrderByEmpNumberDesc(prefix)
                .map(e -> e.getEmpNumber().substring(prefix.length()))
                .filter(rest -> rest.matches("\\d+"))
                .map(rest -> Integer.parseInt(rest) + 1)
                .orElse(1);
        String candidate = String.format("%s%04d", prefix, next);
        while (employeeRepository.existsByEmpNumberIgnoreCase(candidate)) {
            candidate = String.format("%s%04d", prefix, ++next);
        }
        return candidate;
    }

    private void checkCanManage(AuthUser me, Employee target) {
        if (me.isOwner()) {
            return;
        }
        boolean staff = target.getRole() == Role.MANAGER || target.getRole() == Role.EMPLOYEE;
        if (!staff || !inScope(me, brandIdOf(target))) {
            throw ApiException.forbidden("You are not allowed to change this employee");
        }
    }

    private void checkCanAssign(AuthUser me, Role role, Long brandId) {
        boolean staff = role == Role.MANAGER || role == Role.EMPLOYEE;
        if (staff && brandId == null) {
            throw ApiException.badRequest("Choose a brand for managers and employees");
        }
        if (me.isOwner()) {
            return;
        }
        if (!staff) {
            throw ApiException.forbidden("Only an owner can create owners and admins");
        }
        if (!inScope(me, brandId)) {
            throw ApiException.forbidden("You can only add employees to your own brand");
        }
    }

    private boolean inScope(AuthUser me, Long brandId) {
        return me.isOwner() || me.brandId() == null || me.brandId().equals(brandId);
    }

    private Employee find(Long id) {
        return employeeRepository.findWithBrandById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
    }

    private static Long brandIdOf(Employee e) {
        return e.getBrand() == null ? null : e.getBrand().getId();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String lowerOrNull(String s) {
        String t = trimToNull(s);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }
}
