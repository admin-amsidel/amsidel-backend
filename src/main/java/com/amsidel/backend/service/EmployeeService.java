package com.amsidel.backend.service;

import com.amsidel.backend.dto.EmployeeRequest;
import com.amsidel.backend.dto.EmployeeResponse;
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
@Service
@RequiredArgsConstructor
public class EmployeeService {

    /** Employee numbers for people without a brand (owners, group admins). */
    private static final String GROUP_PREFIX = "GRP";

    private final EmployeeRepository employeeRepository;
    private final BrandRepository brandRepository;
    private final DesignationRepository designationRepository;
    private final PasswordEncoder passwordEncoder;

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
        checkCanAssign(me, request.getRole(), request.getBrandId());
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw ApiException.badRequest(PinPolicy.RULE);
        }

        Employee employee = new Employee();
        employee.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        employee.setMustChangePassword(true);
        employee.setPasswordChangedAt(Instant.now());
        apply(employee, request);
        return EmployeeResponse.from(employeeRepository.save(employee));
    }

    @Transactional
    public EmployeeResponse update(AuthUser me, Long id, EmployeeRequest request) {
        Employee employee = find(id);
        checkCanManage(me, employee);
        checkCanAssign(me, request.getRole(), request.getBrandId());
        if (employee.getId().equals(me.id())) {
            if (request.getRole() != employee.getRole()) {
                throw ApiException.badRequest("You cannot change your own role");
            }
            if (Boolean.FALSE.equals(request.getActive())) {
                throw ApiException.badRequest("You cannot disable your own account");
            }
        }
        apply(employee, request);
        return EmployeeResponse.from(employee);
    }

    /** Sets a temporary PIN, unlocks the account and signs the person out everywhere. */
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

    /** The employee, when {@code me} may change their record (and photo). */
    @Transactional(readOnly = true)
    public Employee findManageable(AuthUser me, Long id) {
        Employee employee = find(id);
        checkCanManage(me, employee);
        return employee;
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
        employee.setName(request.getName().trim());
        employee.setAddress(trimToNull(request.getAddress()));
        employee.setPhone(trimToNull(request.getPhone()));
        employee.setPhotoPath(trimToNull(request.getPhotoPath()));
        employee.setJoinedDate(request.getJoinedDate());
        employee.setDob(request.getDob());
        employee.setBloodGroup(trimToNull(request.getBloodGroup()));
        employee.setPersonalEmail(lowerOrNull(request.getPersonalEmail()));
        employee.setCompanyEmail(lowerOrNull(request.getCompanyEmail()));
        employee.setEmergencyContact(trimToNull(request.getEmergencyContact()));
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
        return designation;
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

    /** Next free number for the brand, e.g. DW0001, DW0002 ... (GRP0001 for group-level people). */
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
