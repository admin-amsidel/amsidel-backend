/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.scheduler;

import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.LeavePlan;
import com.amsidel.backend.entity.Role;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.LeavePlanRepository;
import com.amsidel.backend.service.BrandMailSenders;
import com.amsidel.backend.service.LeavePlanService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Every morning: a brand's leave plan ending within 10 days with no next plan, owners and that brand's admins get one
 * email (tried again the next day if it could not go), through the brand's own Brevo account or the default one. The
 * alert in the portal stays until the next plan is added.
 */
@Slf4j
@Component
public class LeaveExpiryNotifier {

    private final LeavePlanRepository plans;
    private final EmployeeRepository employees;
    private final BrandMailSenders brandSenders;
    private final ObjectProvider<JavaMailSender> defaultSender;
    private final String from;
    private final String fromName;
    private final boolean signInReady;
    private final ZoneId zone;

    public LeaveExpiryNotifier(LeavePlanRepository plans, EmployeeRepository employees, BrandMailSenders brandSenders,
                               ObjectProvider<JavaMailSender> defaultSender,
                               @Value("${spring.mail.username:}") String username, @Value("${spring.mail.password:}") String password,
                               @Value("${app.mail.from:}") String from, @Value("${app.mail.from-name:Amsidel Group}") String fromName,
                               @Value("${app.leave.zone}") ZoneId zone) {
        this.plans = plans;
        this.employees = employees;
        this.brandSenders = brandSenders;
        this.defaultSender = defaultSender;
        this.from = StringUtils.hasText(from) ? from.trim() : username.trim();
        this.fromName = fromName;
        this.signInReady = !StringUtils.hasText(username) || StringUtils.hasText(password);
        this.zone = zone;
    }

    @Scheduled(cron = "${app.leave.expiry-cron:0 0 8 * * *}", zone = "${app.leave.zone:Asia/Kolkata}")
    @Transactional
    public void run() {
        LocalDate today = LocalDate.now(zone);
        List<LeavePlan> all = plans.allFull();
        for (LeavePlan p : all) {
            long left = ChronoUnit.DAYS.between(today, p.getValidUntil());
            if (p.getExpiryNotifiedAt() != null || !p.covers(today) || left > LeavePlanService.NOTICE_DAYS || p.getBrand() == null || !p.getBrand().isActive()) {
                continue;
            }
            Long brandId = p.getBrand().getId();
            boolean next = all.stream().anyMatch(o -> Objects.equals(o.getBrand() == null ? null : o.getBrand().getId(), brandId)
                    && o.getValidFrom().isAfter(p.getValidUntil()));
            if (!next && tell(p, left)) {
                p.setExpiryNotifiedAt(Instant.now());
            }
        }
    }

    /** True when the email went out. */
    private boolean tell(LeavePlan p, long left) {
        Brand brand = p.getBrand();
        String[] to = employees.search(null).stream()
                .filter(e -> e.isActive() && inCharge(e, brand))
                .map(LeaveExpiryNotifier::address)
                .filter(StringUtils::hasText)
                .distinct()
                .toArray(String[]::new);
        if (to.length == 0) {
            log.warn("The leave plan of {} ends on {}, but no owner or admin has an email on record", brand.getCode(), p.getValidUntil());
            return false;
        }
        BrandMailSenders.Route route = brandSenders.route(brand.getId()).orElse(null);
        JavaMailSender sender = route != null ? route.sender() : signInReady ? defaultSender.getIfAvailable() : null;
        String fromAddress = route != null ? route.from() : from;
        if (sender == null || !StringUtils.hasText(fromAddress)) {
            log.warn("The leave plan of {} ends on {}, but mail is not set up", brand.getCode(), p.getValidUntil());
            return false;
        }
        String text = "The leave plan of " + brand.getName() + " (" + brand.getCode() + ") ends on " + p.getValidUntil()
                + (left == 0 ? ", today" : ", in " + left + (left == 1 ? " day" : " days")) + ".\n\n"
                + "Add the next plan under Leave > Leave settings before then. After it ends, nobody in this brand can apply for leave "
                + "until a new plan is in force, and leave set to carry forward moves on only to the next plan.";
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper mail = new MimeMessageHelper(message, false, "UTF-8");
            mail.setFrom(fromAddress, route != null && StringUtils.hasText(route.fromName()) ? route.fromName() : fromName);
            mail.setTo(to);
            mail.setSubject("Leave plan of " + brand.getCode() + " ends on " + p.getValidUntil());
            mail.setText(text);
            sender.send(message);
            log.info("Told {} owner(s) and admin(s) that the leave plan of {} ends on {}", to.length, brand.getCode(), p.getValidUntil());
            return true;
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.warn("Could not tell anyone that the leave plan of {} ends on {}: {}", brand.getCode(), p.getValidUntil(), e.getMessage());
            return false;
        }
    }

    private static boolean inCharge(Employee e, Brand brand) {
        Long own = e.getBrand() == null ? null : e.getBrand().getId();
        return e.getRole() == Role.OWNER || (e.getRole() == Role.ADMIN && (own == null || own.equals(brand.getId())));
    }

    private static String address(Employee e) {
        return StringUtils.hasText(e.getCompanyEmail()) ? e.getCompanyEmail().trim()
                : StringUtils.hasText(e.getPersonalEmail()) ? e.getPersonalEmail().trim() : null;
    }
}
