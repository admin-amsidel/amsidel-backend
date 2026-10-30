/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.MailFile;
import com.amsidel.backend.dto.MailResult;
import com.amsidel.backend.dto.MailSendRequest;
import com.amsidel.backend.dto.MailSetup;
import com.amsidel.backend.dto.PartyMailRequest;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.MailFileStore;
import com.amsidel.backend.service.MailService;
import com.amsidel.backend.service.TradeMailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Customer and supplier mail (Product portal): the same calls as staff mail, under the Customer & supplier mail menu. */
@RestController
@RequestMapping("/api/trade/mail")
@RequiredArgsConstructor
@Checkpoint({Menus.PROD_MAIL})
public class TradeMailController {

    private final TradeMailService tradeMail;
    private final MailService mailService;
    private final MailFileStore files;

    @GetMapping("/setup")
    public MailSetup setup() {
        return mailService.setup();
    }

    /** Who can be mailed: the active customers and suppliers of my brands (or one brand). */
    @GetMapping("/recipients")
    public TradeMailService.Recipients recipients(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return tradeMail.recipients(me, brandId);
    }

    /** My mail sign-off (the same one as for staff mail). */
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
        return files.put(me, file);
    }

    @DeleteMapping("/files/{id}")
    public void remove(@AuthenticationPrincipal AuthUser me, @PathVariable String id) {
        files.remove(me, id);
    }
}
