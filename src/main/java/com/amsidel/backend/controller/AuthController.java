package com.amsidel.backend.controller;

import com.amsidel.backend.dto.ChangePasswordRequest;
import com.amsidel.backend.dto.EmployeeResponse;
import com.amsidel.backend.dto.LoginRequest;
import com.amsidel.backend.dto.LoginResponse;
import com.amsidel.backend.dto.MyAccess;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.service.AuthService;
import com.amsidel.backend.service.MenuAccessService;
import com.amsidel.backend.service.PhotoService;
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

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
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

    @PostMapping("/change-password")
    public LoginResponse changePassword(@AuthenticationPrincipal AuthUser me,
                                        @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(me, request);
    }

    @GetMapping("/me/photo")
    public ResponseEntity<byte[]> myPhoto(@AuthenticationPrincipal AuthUser me) {
        return PhotoResponses.image(photoService.myPhoto(me));
    }

    /** Multipart form with one part named "file" (JPEG, PNG or WebP, up to 2 MB). */
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
