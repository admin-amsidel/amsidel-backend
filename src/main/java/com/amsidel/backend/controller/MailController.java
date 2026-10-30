/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.MailFile;
import com.amsidel.backend.dto.MailResult;
import com.amsidel.backend.dto.MailSendRequest;
import com.amsidel.backend.dto.MailSetup;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.MailFileStore;
import com.amsidel.backend.service.MailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Staff mail: needs the Staff mail menu. */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
@Checkpoint({Menus.STAFF_MAIL})
public class MailController {

    private final MailService mailService;
    private final MailFileStore files;

    @GetMapping("/setup")
    public MailSetup setup() {
        return mailService.setup();
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
        return mailService.send(me, request);
    }

    /** An attachment or a picture for the message, uploaded once before sending (kept a few hours). */
    @PostMapping("/files")
    public MailFile upload(@AuthenticationPrincipal AuthUser me, @RequestParam("file") MultipartFile file) {
        return files.put(me, file);
    }

    @DeleteMapping("/files/{id}")
    public void remove(@AuthenticationPrincipal AuthUser me, @PathVariable String id) {
        files.remove(me, id);
    }
}
