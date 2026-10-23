package com.amsidel.backend.config;

import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Designation;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.LeaveAllocation;
import com.amsidel.backend.entity.LeaveType;
import com.amsidel.backend.entity.Role;
import com.amsidel.backend.entity.WorkHourRule;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.DesignationRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.LeaveTypeRepository;
import com.amsidel.backend.repository.WorkHourRuleRepository;
import com.amsidel.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * First start on an empty database: creates the first brand, an owner account, the designation levels, the
 * default work hour rule and the standard leave types, so someone can sign in and add everyone else from the portal. Each part is skipped once
 * its table has rows.
 */
@Slf4j
@Component
@Order(1) // before MenuBootstrap, which gives some menus to designations made here
@RequiredArgsConstructor
public class DataBootstrap implements ApplicationRunner {

    private final BrandRepository brandRepository;
    private final EmployeeRepository employeeRepository;
    private final DesignationRepository designationRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final WorkHourRuleRepository workHourRuleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.brand.code}")
    private String brandCode;

    @Value("${app.bootstrap.brand.name}")
    private String brandName;

    @Value("${app.bootstrap.owner.user-id}")
    private String ownerUserId;

    @Value("${app.bootstrap.owner.password}")
    private String ownerPassword;

    @Value("${app.bootstrap.owner.name}")
    private String ownerName;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (brandRepository.count() == 0) {
            Brand brand = new Brand();
            brand.setCode(brandCode);
            brand.setName(brandName);
            brandRepository.save(brand);
            log.info("Created first brand {} ({})", brandName, brandCode);
        }

        if (employeeRepository.count() == 0) {
            Employee owner = new Employee();
            owner.setEmpNumber("GRP0001");
            owner.setUserId(AuthService.normalizeUserId(ownerUserId));
            owner.setPasswordHash(passwordEncoder.encode(ownerPassword));
            owner.setPasswordChangedAt(Instant.now());
            owner.setMustChangePassword(true);
            owner.setRole(Role.OWNER);
            owner.setName(ownerName);
            owner.setJoinedDate(LocalDate.now());
            employeeRepository.save(owner);
            log.warn("Created first owner account '{}'. Sign in and change its PIN now.", owner.getUserId());
        }

        if (designationRepository.count() == 0) {
            // the group's level plan; owners change it under Designations
            designation(1, "1", "Trainee");
            designation(2, "2", "Associate");
            designation(3, "3", "Team Member");
            designation(4, "4", "Team Lead");
            designation(5, "5", "Team Manager");
            designation(6, "6.1", "Branch Manager / Head");
            designation(6, "6.2", "Finance Manager");
            designation(7, "7", "Regional Head / Manager");
            designation(8, "8.1", "Admin");
            designation(8, "8.2", "HR Head / Manager");
            designation(9, "9", "CIO");
            designation(10, "10", "CEO");
            log.info("Created the designation levels");
        }

        if (workHourRuleRepository.count() == 0) {
            // default minimum hours for every level: 9 a day, 54 a week (Monday to Saturday);
            // under 4.5 hours is flagged Half Day Leave, under 1 hour Full Day Leave
            WorkHourRule rule = new WorkHourRule();
            rule.setLevelNumber(WorkHourRule.DEFAULT_LEVEL);
            rule.setDailyMinHours(new BigDecimal("9"));
            rule.setWeeklyMinHours(new BigDecimal("54"));
            rule.setHalfDayBelow(new BigDecimal("4.5"));
            rule.setFullDayBelow(new BigDecimal("1"));
            workHourRuleRepository.save(rule);
            log.info("Created the default work hour rule");
        }

        if (leaveTypeRepository.count() == 0) {
            // starting allowances; owners and admins change them under Leave settings
            leaveType(1, "CL", "Casual Leave", LeaveAllocation.ANNUAL, 10, true, false);
            leaveType(2, "SL", "Sick Leave", LeaveAllocation.ANNUAL, 10, true, false);
            leaveType(3, "EL", "Earned Leave", LeaveAllocation.ANNUAL, 10, false, false);
            leaveType(4, "CO", "Compensatory Off", LeaveAllocation.GRANTED, null, true, false);
            leaveType(5, "PL", "Paternity Leave", LeaveAllocation.GRANTED, null, false, false);
            leaveType(6, "RH", "Restricted Holiday", LeaveAllocation.ANNUAL, 5, false, true);
            leaveType(7, "LWP", "Leave Without Pay", LeaveAllocation.UNLIMITED, null, true, false);
            log.info("Created the standard leave types");
        }
    }

    private void designation(int levelNumber, String levelCode, String name) {
        Designation designation = new Designation();
        designation.setLevelNumber(levelNumber);
        designation.setLevelCode(levelCode);
        designation.setName(name);
        designationRepository.save(designation);
    }

    private void leaveType(int order, String code, String name, LeaveAllocation allocation, Integer quota,
                           boolean halfDay, boolean restrictedDates) {
        LeaveType type = new LeaveType();
        type.setSortOrder(order);
        type.setCode(code);
        type.setName(name);
        type.setAllocation(allocation);
        type.setAnnualQuota(quota);
        type.setHalfDayAllowed(halfDay);
        type.setRestrictedDatesOnly(restrictedDates);
        leaveTypeRepository.save(type);
    }
}
