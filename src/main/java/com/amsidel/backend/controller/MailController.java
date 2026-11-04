/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.MailAccountRequest;
import com.amsidel.backend.dto.MailConfigRequest;
import com.amsidel.backend.dto.MailFile;
import com.amsidel.backend.dto.MailResult;
import com.amsidel.backend.dto.MailSendRequest;
import com.amsidel.backend.dto.MailSetup;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.BrandMailSenders;
import com.amsidel.backend.service.EmployeeService;
import com.amsidel.backend.service.MailFileStore;
import com.amsidel.backend.service.MailLimitsService;
import com.amsidel.backend.service.MailService;
import com.amsidel.backend.service.StaffMailConfig;
import com.amsidel.backend.service.StaffMailRules;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Staff mail: needs the Staff mail menu. */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
@Checkpoint({Menus.STAFF_MAIL})
public class MailController {

    private final MailService mailService;
    private final MailFileStore files;
    private final StaffMailRules rules;
    private final StaffMailConfig config;
    private final EmployeeService employees;

    @GetMapping("/setup")
    public MailSetup setup() {
        return mailService.setup();
    }

    /** Each brand's limits and today's count, for the brands I may mail. */
    @GetMapping("/rules")
    public List<MailLimitsService.BrandLimits> rules(@AuthenticationPrincipal AuthUser me) {
        return config.rules(me);
    }

    /** Email > Staff email config: each brand's limits and today's count. */
    @Checkpoint({Menus.MAIL_CONFIG})
    @GetMapping("/config")
    public List<MailLimitsService.BrandLimits> config(@AuthenticationPrincipal AuthUser me) {
        return config.rows(me);
    }

    @Checkpoint({Menus.MAIL_CONFIG})
    @PutMapping("/config")
    public List<MailLimitsService.BrandLimits> saveConfig(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody MailConfigRequest body) {
        return config.save(me, body);
    }

    /** The brand's own Brevo account, for all its mail (the key is saved, never sent back). */
    @Checkpoint({Menus.MAIL_CONFIG})
    @PutMapping("/config/brevo")
    public List<MailLimitsService.BrandLimits> saveBrevo(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody MailAccountRequest body) {
        return config.saveAccount(me, body);
    }

    /** The brand goes back to the default account. */
    @Checkpoint({Menus.MAIL_CONFIG})
    @DeleteMapping("/config/brevo")
    public List<MailLimitsService.BrandLimits> removeBrevo(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return config.removeAccount(me, brandId);
    }

    /** Signs in to Brevo with the saved account; nothing is sent. */
    @Checkpoint({Menus.MAIL_CONFIG})
    @PostMapping("/config/brevo/test")
    public BrandMailSenders.TestResult testBrevo(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return config.testAccount(me, brandId);
    }

    /** My sign-off for staff mail: the saved one, or the standard one (saved = false). */
    @GetMapping("/signature")
    public MailService.SignatureView signature(@AuthenticationPrincipal AuthUser me) {
        return mailService.signature(me);
    }

    /** Saves my sign-off; empty goes back to the standard one. */
    @PutMapping("/signature")
    public MailService.SignatureView saveSignature(@AuthenticationPrincipal AuthUser me,
                                                   @Valid @RequestBody MailSendRequest.Signature body) {
        return mailService.saveSignature(me, body.getSignature());
    }

    /** One personal mail to one employee; 200 with sent=false when this person's mail could not go out. */
    @PostMapping("/send")
    public MailResult send(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody MailSendRequest request) {
        // the person first: one I may mail; the email counts for their brand
        long brand = StaffMailConfig.brandKeyOf(employees.findVisible(me, request.getEmployeeId()));
        rules.checkEmail(me, request.getEmployeeId(), brand, request.getImages() != null && !request.getImages().isEmpty(),
                request.getAttachments() != null && !request.getAttachments().isEmpty());
        config.take(brand);
        boolean sent = false;
        try {
            MailResult result = mailService.send(me, request, config.look(brand), config.alwaysCc(brand), brand);
            sent = result.sent();
            return result;
        } finally {
            // only an email that went out stays counted
            if (!sent) {
                config.giveBack(brand);
            }
        }
    }

    /** An attachment or a picture for the message, uploaded once before sending (kept a few hours). */
    @PostMapping("/files")
    public MailFile upload(@AuthenticationPrincipal AuthUser me, @RequestParam("file") MultipartFile file) {
        rules.checkUpload(me);
        return files.put(me, file);
    }

    @DeleteMapping("/files/{id}")
    public void remove(@AuthenticationPrincipal AuthUser me, @PathVariable String id) {
        files.remove(me, id);
    }
}
