/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.Role;
import com.amsidel.backend.entity.UserType;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.UserTypeRepository;
import com.amsidel.backend.security.SignedInCache;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * User types (portal access): the four built in, and the ones owners and admins add. Changing a type's access level
 * moves its people to that level; a type is deleted only while nobody has it, and its menu access goes with it.
 */
@Service
@RequiredArgsConstructor
public class UserTypeService {

    private static final Map<Role, String> BUILT_IN = Map.of(Role.OWNER, "Owner", Role.ADMIN, "Admin", Role.MANAGER, "Manager",
            Role.EMPLOYEE, "Employee");

    private final UserTypeRepository repository;
    private final EmployeeRepository employees;
    private final MenuAccessService menuAccess;
    private final SignedInCache signedIn;
    private final com.amsidel.backend.repository.BrandRepository brands;

    @Data
    public static class Body {
        @NotBlank(message = "Give the name")
        @Size(max = 60)
        private String name;

        /** ADMIN, MANAGER or EMPLOYEE (owners are only the built-in Owner). */
        @NotNull(message = "Choose the access level")
        private Role baseRole;

        @Size(max = 200)
        private String description;

        private Boolean active;

        /** The brand it is for; empty = every brand. */
        private Long brandId;
    }

    /** A user type with how many people have it (none: it may be deleted). */
    public record View(Long id, String code, String name, Role baseRole, boolean builtIn, boolean active, String description, long employees,
                       Long brandId, String brandCode, String brandName) {
    }

    /** The built-in types, once (the code is the role name, so the menu access set before stays theirs). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Transactional
    public void seed() {
        for (Role r : Role.values()) {
            if (!repository.existsByCode(r.name())) {
                UserType t = new UserType();
                t.setCode(r.name());
                t.setName(BUILT_IN.get(r));
                t.setBaseRole(r);
                t.setBuiltIn(true);
                t.setCreatedAt(Instant.now());
                repository.save(t);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<View> list() {
        Map<Long, Long> counts = counts();
        return repository.listWithBrands().stream().map(t -> view(t, counts)).toList();
    }

    @Transactional
    public View create(com.amsidel.backend.security.AuthUser me, Body body) {
        checkLevel(body.getBaseRole());
        com.amsidel.backend.entity.Brand brand = brandFor(me, body.getBrandId());
        String name = body.getName().strip();
        if (repository.nameTaken(name, brand == null ? null : brand.getId(), -1L)) {
            throw ApiException.conflict("There is already a role \"" + name + "\" for " + (brand == null ? "every brand" : brand.getName()));
        }
        UserType t = new UserType();
        t.setBrand(brand);
        t.setCode(newCode(name));
        t.setName(name);
        t.setBaseRole(body.getBaseRole());
        t.setDescription(CatalogService.blank(body.getDescription()));
        t.setActive(body.getActive() == null || body.getActive());
        t.setCreatedAt(Instant.now());
        repository.save(t);
        return view(t, counts());
    }

    /** Only one nobody has: a role in use stays as it is (give its people another role first). */
    @Transactional
    public View update(com.amsidel.backend.security.AuthUser me, Long id, Body body) {
        UserType t = find(id);
        String name = body.getName().strip();
        if (t.isBuiltIn() && (body.getBaseRole() != t.getBaseRole() || Boolean.FALSE.equals(body.getActive()) || body.getBrandId() != null)) {
            throw ApiException.badRequest("A built-in role keeps its access level, stays on and is for every brand; only its name and note change");
        }
        long n = counts().getOrDefault(id, 0L);
        if (n > 0) {
            throw ApiException.conflict(n + (n == 1 ? " employee has" : " employees have") + " the role \"" + t.getName()
                    + "\": it cannot be changed while in use. Give them another role first");
        }
        brandFor(me, t.getBrand() == null ? null : t.getBrand().getId());
        com.amsidel.backend.entity.Brand brand = t.isBuiltIn() ? null : brandFor(me, body.getBrandId());
        if (repository.nameTaken(name, brand == null ? null : brand.getId(), id)) {
            throw ApiException.conflict("There is already a role \"" + name + "\" for " + (brand == null ? "every brand" : brand.getName()));
        }
        t.setBrand(brand);
        if (t.isBuiltIn()) {
            if (body.getBaseRole() != t.getBaseRole() || Boolean.FALSE.equals(body.getActive())) {
                throw ApiException.badRequest("A built-in user type keeps its access level and stays on; only its name and note change");
            }
        } else {
            checkLevel(body.getBaseRole());
            if (body.getBaseRole() != t.getBaseRole()) {
                // its people move to the new level with it
                List<Employee> people = employees.findByUserType_Id(id);
                people.forEach(e -> e.setRole(body.getBaseRole()));
                t.setBaseRole(body.getBaseRole());
                signedIn.evictAll();
            }
            if (body.getActive() != null) {
                t.setActive(body.getActive());
            }
        }
        t.setName(name);
        t.setDescription(CatalogService.blank(body.getDescription()));
        t.setUpdatedAt(Instant.now());
        return view(t, counts());
    }

    /** Only one nobody has, and not a built-in one; its menu access goes too. */
    @Transactional
    public void delete(com.amsidel.backend.security.AuthUser me, Long id) {
        UserType t = find(id);
        brandFor(me, t.getBrand() == null ? null : t.getBrand().getId());
        if (t.isBuiltIn()) {
            throw ApiException.badRequest("The built-in user types (Owner, Admin, Manager, Employee) cannot be deleted");
        }
        long n = employees.countByUserType_Id(id);
        if (n > 0) {
            throw ApiException.conflict(n + (n == 1 ? " employee has" : " employees have") + " the role \"" + t.getName()
                    + "\": it cannot be deleted while in use. Give them another role first");
        }
        menuAccess.forget(AccessSubject.ROLE, t.getCode());
        repository.delete(t);
    }

    /**
     * The type an employee gets: the one chosen (active, unless they already have it; a brand's own only for that brand's
     * people), else the built-in one of the role.
     */
    @Transactional(readOnly = true)
    public UserType forEmployee(Long userTypeId, Role role, UserType current, Long brandId) {
        if (userTypeId == null) {
            return repository.findByCode(role.name()).orElse(null);
        }
        UserType t = find(userTypeId);
        if (t.getBrand() != null && !t.getBrand().getId().equals(brandId)) {
            throw ApiException.badRequest("The role \"" + t.getName() + "\" is for " + t.getBrand().getName() + "'s people only");
        }
        if (!t.isActive() && (current == null || !current.getId().equals(t.getId()))) {
            throw ApiException.badRequest("The user type \"" + t.getName() + "\" is switched off");
        }
        return t;
    }

    /** People per type id; those with no type yet count for the built-in one of their role. */
    private Map<Long, Long> counts() {
        Map<Long, Long> counts = new java.util.HashMap<>(employees.countByUserType());
        for (Object[] r : employees.countWithoutUserTypeByRole()) {
            repository.findByCode(((Role) r[0]).name()).ifPresent(t -> counts.merge(t.getId(), ((Number) r[1]).longValue(), Long::sum));
        }
        return counts;
    }

    /**
     * The brand a role may be made or changed for: owners and admins without a brand may choose any (or every brand); a
     * brand's admin only their own brand's roles.
     */
    private com.amsidel.backend.entity.Brand brandFor(com.amsidel.backend.security.AuthUser me, Long brandId) {
        boolean wholeGroup = me.isOwner() || me.isGroupAdmin();
        if (!wholeGroup && (brandId == null || !brandId.equals(me.brandId()))) {
            throw ApiException.forbidden(brandId == null ? "Only owners and group admins manage the roles for every brand"
                    : "You can only manage your own brand's roles");
        }
        return brandId == null ? null : brands.findById(brandId).orElseThrow(() -> ApiException.badRequest("Brand not found: " + brandId));
    }

    private UserType find(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User type not found: " + id));
    }

    private static void checkLevel(Role r) {
        if (r == Role.OWNER) {
            throw ApiException.badRequest("Only the built-in Owner type gives owner rights; choose Admin, Manager or Employee");
        }
    }

    /** "Site supervisor" -> SITE_SUPERVISOR, made unique. */
    private String newCode(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        if (base.isEmpty()) {
            base = "TYPE";
        }
        base = base.length() > 24 ? base.substring(0, 24).replaceAll("_+$", "") : base;
        String code = base;
        for (int n = 2; repository.existsByCode(code) || isRoleName(code); n++) {
            code = base + "_" + n;
        }
        return code;
    }

    private static boolean isRoleName(String code) {
        for (Role r : Role.values()) {
            if (r.name().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static View view(UserType t, Map<Long, Long> counts) {
        long n = counts.getOrDefault(t.getId(), 0L);
        return new View(t.getId(), t.getCode(), t.getName(), t.getBaseRole(), t.isBuiltIn(), t.isActive(), t.getDescription(), n,
                t.getBrand() == null ? null : t.getBrand().getId(), t.getBrand() == null ? null : t.getBrand().getCode(),
                t.getBrand() == null ? null : t.getBrand().getName());
    }
}
