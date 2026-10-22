package com.amsidel.backend.controller;

import com.amsidel.backend.dto.MailResult;
import com.amsidel.backend.dto.MailSendRequest;
import com.amsidel.backend.dto.MailSetup;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.MailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Staff mail: needs the Staff mail menu. */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
@Checkpoint({Menus.STAFF_MAIL})
public class MailController {

    private final MailService mailService;

    @GetMapping("/setup")
    public MailSetup setup() {
        return mailService.setup();
    }

    /** One personal mail to one employee; 200 with sent=false when this person's mail could not go out. */
    @PostMapping("/send")
    public MailResult send(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody MailSendRequest request) {
        return mailService.send(me, request);
    }
}
