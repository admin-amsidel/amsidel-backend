/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.ChangePasswordRequest;
import com.amsidel.backend.dto.EmployeeResponse;
import com.amsidel.backend.dto.LoginRequest;
import com.amsidel.backend.dto.LoginResponse;
import com.amsidel.backend.dto.MyAccess;
import com.amsidel.backend.dto.MyDetailsRequest;
import com.amsidel.backend.service.EmployeeService;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.dto.BankDetails;
import com.amsidel.backend.service.AuthService;
import com.amsidel.backend.service.BankDetailsService;
import com.amsidel.backend.service.MenuAccessService;
import com.amsidel.backend.service.PhotoService;
import com.amsidel.backend.service.UploadLimits;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PhotoService photoService;
    private final MenuAccessService menuAccessService;
    private final BankDetailsService bankDetailsService;
    private final EmployeeService employeeService;
    private final UploadLimits uploadLimits;
    private final com.amsidel.backend.service.SessionService sessions;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** Ends my session, so I no longer count as signed in and my token stops working. */
    @PostMapping("/logout")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthUser me) {
        sessions.signOut(me);
    }

    @GetMapping("/me")
    public EmployeeResponse me(@AuthenticationPrincipal AuthUser me) {
        return authService.me(me);
    }

    /** The menus I may open (from menu access), for the portal's sidebar and page guards. */
    @GetMapping("/access")
    public MyAccess access(@AuthenticationPrincipal AuthUser me) {
        return menuAccessService.myAccess(me);
    }

    /** How large uploads may be, for the portal's checks and hints. */
    @GetMapping("/upload-limits")
    public UploadLimits.View uploadLimits() {
        return uploadLimits.view();
    }

    @PostMapping("/change-password")
    public LoginResponse changePassword(@AuthenticationPrincipal AuthUser me,
                                        @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(me, request);
    }

    /** My own personal details: phone, personal email, emergency contact, address, date of birth, blood group. */
    @PutMapping("/me/details")
    public EmployeeResponse updateMyDetails(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody MyDetailsRequest request) {
        return employeeService.updateMine(me, request);
    }

    /** My own PAN and salary account, read-only (set by admins or payroll). */
    @GetMapping("/me/bank-details")
    public BankDetails.View myBankDetails(@AuthenticationPrincipal AuthUser me) {
        BankDetails.View v = bankDetailsService.get(me, me.id());
        return new BankDetails.View(v.pan(), v.bankAccount(), v.ifsc(), v.upi(), false);
    }

    @GetMapping("/me/photo")
    public ResponseEntity<byte[]> myPhoto(@AuthenticationPrincipal AuthUser me) {
        return PhotoResponses.image(photoService.myPhoto(me));
    }

    /** Multipart form with one part named "file" (JPEG, PNG or WebP). */
    @PostMapping(path = "/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeResponse uploadMyPhoto(@AuthenticationPrincipal AuthUser me,
                                          @RequestParam("file") MultipartFile file) {
        return photoService.uploadMine(me, file);
    }

    @DeleteMapping("/me/photo")
    public EmployeeResponse removeMyPhoto(@AuthenticationPrincipal AuthUser me) {
        return photoService.removeMine(me);
    }
}
