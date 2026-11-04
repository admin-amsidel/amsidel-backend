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
import com.amsidel.backend.dto.PartyMailRequest;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.MailFileStore;
import com.amsidel.backend.service.BrandMailSenders;
import com.amsidel.backend.service.MailLimitsService;
import com.amsidel.backend.service.MailService;
import com.amsidel.backend.service.TradeMailConfig;
import com.amsidel.backend.service.TradeMailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Customer and supplier mail (Customer portal): the same calls as staff email, under the Customer & supplier email menu. */
@RestController
@RequestMapping("/api/trade/mail")
@RequiredArgsConstructor
@Checkpoint({Menus.PROD_MAIL})
public class TradeMailController {

    private final TradeMailService tradeMail;
    private final MailService mailService;
    private final MailFileStore files;
    private final TradeMailConfig config;

    @GetMapping("/setup")
    public MailSetup setup() {
        return mailService.setup();
    }

    /** Who can be mailed: the active customers and suppliers of my brands (or one brand). */
    @GetMapping("/recipients")
    public TradeMailService.Recipients recipients(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return tradeMail.recipients(me, brandId);
    }

    /** Each brand's limits, office copy and today's count, for the brands I may mail. */
    @GetMapping("/rules")
    public List<MailLimitsService.BrandLimits> rules(@AuthenticationPrincipal AuthUser me) {
        return config.rules(me);
    }

    /** Email > Customer & supplier email config. */
    @Checkpoint({Menus.TMAIL_CONFIG})
    @GetMapping("/config")
    public List<MailLimitsService.BrandLimits> config(@AuthenticationPrincipal AuthUser me) {
        return config.rows(me);
    }

    @Checkpoint({Menus.TMAIL_CONFIG})
    @PutMapping("/config")
    public List<MailLimitsService.BrandLimits> saveConfig(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody MailConfigRequest body) {
        return config.save(me, body);
    }

    /** The brand's own Brevo account, for all its mail (the key is saved, never sent back). */
    @Checkpoint({Menus.TMAIL_CONFIG})
    @PutMapping("/config/brevo")
    public List<MailLimitsService.BrandLimits> saveBrevo(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody MailAccountRequest body) {
        return config.saveAccount(me, body);
    }

    /** The brand goes back to the default account. */
    @Checkpoint({Menus.TMAIL_CONFIG})
    @DeleteMapping("/config/brevo")
    public List<MailLimitsService.BrandLimits> removeBrevo(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return config.removeAccount(me, brandId);
    }

    /** Signs in to Brevo with the saved account; nothing is sent. */
    @Checkpoint({Menus.TMAIL_CONFIG})
    @PostMapping("/config/brevo/test")
    public BrandMailSenders.TestResult testBrevo(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return config.testAccount(me, brandId);
    }

    /** My mail sign-off (the same one as for staff email). */
    @GetMapping("/signature")
    public MailService.SignatureView signature(@AuthenticationPrincipal AuthUser me) {
        return mailService.signature(me);
    }

    @PutMapping("/signature")
    public MailService.SignatureView saveSignature(@AuthenticationPrincipal AuthUser me,
                                                   @Valid @RequestBody MailSendRequest.Signature body) {
        return mailService.saveSignature(me, body.getSignature());
    }

    /** One personal mail to one customer or supplier; 200 with sent=false when this one could not go out. */
    @PostMapping("/send")
    public MailResult send(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody PartyMailRequest request) {
        return tradeMail.send(me, request);
    }

    @PostMapping("/files")
    public MailFile upload(@AuthenticationPrincipal AuthUser me, @RequestParam("file") MultipartFile file) {
        tradeMail.checkUpload(me);
        return files.put(me, file);
    }

    @DeleteMapping("/files/{id}")
    public void remove(@AuthenticationPrincipal AuthUser me, @PathVariable String id) {
        files.remove(me, id);
    }
}
