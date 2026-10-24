package com.amsidel.backend.service;

import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/**
 * Which brands' products, customers and bills someone works with: owners and people without a brand see every
 * brand; anyone else only their own brand. The menus (checkpoints) decide which pages they may use at all.
 */
@Component
public class TradeAccess {

    private final BrandRepository brandRepository;
    private final EmployeeRepository employeeRepository;
    private final MenuAccessService menuAccess;
    private final ZoneId zone;

    public TradeAccess(BrandRepository brandRepository, EmployeeRepository employeeRepository, MenuAccessService menuAccess,
                       @Value("${app.leave.zone}") ZoneId zone) {
        this.brandRepository = brandRepository;
        this.employeeRepository = employeeRepository;
        this.menuAccess = menuAccess;
        this.zone = zone;
    }

    /** Today in the office time zone. */
    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public boolean allBrands(AuthUser me) {
        return me.isOwner() || me.brandId() == null;
    }

    /** The brands I may pick. */
    public List<Brand> brands(AuthUser me) {
        if (allBrands(me)) {
            return brandRepository.findAll().stream().filter(Brand::isActive)
                    .sorted(Comparator.comparing(Brand::getCode)).toList();
        }
        return brandRepository.findById(me.brandId()).map(List::of).orElse(List.of());
    }

    /** A list filter: the brand asked for (if I may see it), or null = all my brands. */
    public Long scope(AuthUser me, Long brandId) {
        if (allBrands(me)) {
            return brandId;
        }
        if (brandId != null && !brandId.equals(me.brandId())) {
            throw ApiException.forbidden("You can only work with your own brand");
        }
        return me.brandId();
    }

    /**
     * The brand a new record goes to: the one chosen (owners and group admins may choose), else my own assigned brand;
     * someone without a brand who may use several must choose.
     */
    public Brand brandFor(AuthUser me, Long brandId) {
        Long id = allBrands(me) ? (brandId != null ? brandId : me.brandId()) : me.brandId();
        if (id == null) {
            List<Brand> mine = brands(me);
            if (mine.size() == 1) {
                return mine.getFirst();
            }
            throw ApiException.badRequest("Choose the brand");
        }
        if (!allBrands(me) && brandId != null && !brandId.equals(me.brandId())) {
            throw ApiException.forbidden("You can only work with your own brand");
        }
        return brandRepository.findById(id).orElseThrow(() -> ApiException.badRequest("Brand not found"));
    }

    /** 403 unless I may work with this brand. */
    public void check(AuthUser me, Brand brand) {
        if (!allBrands(me) && !brand.getId().equals(me.brandId())) {
            throw ApiException.forbidden("This belongs to another brand");
        }
    }

    /** Owners and admins: may delete bills and projects, and change settled projects. */
    public boolean admin(AuthUser me) {
        return me.role() == com.amsidel.backend.entity.Role.OWNER || me.role() == com.amsidel.backend.entity.Role.ADMIN;
    }

    public void requireAdmin(AuthUser me, String message) {
        if (!admin(me)) {
            throw ApiException.forbidden(message);
        }
    }

    /** 403 unless one of these menus is mine. */
    public void require(AuthUser me, String message, String... menus) {
        if (!menuAccess.can(me, menus)) {
            throw ApiException.forbidden(message);
        }
    }

    public Employee me(AuthUser me) {
        return employeeRepository.getReferenceById(me.id());
    }
}
