package com.amsidel.backend.controller;

import com.amsidel.backend.dto.AttendanceDay;
import com.amsidel.backend.dto.AttendanceDaySummary;
import com.amsidel.backend.dto.AttendanceRecord;
import com.amsidel.backend.dto.AttendanceToday;
import com.amsidel.backend.dto.CheckInRequest;
import com.amsidel.backend.dto.CheckOutRequest;
import com.amsidel.backend.dto.TeamMember;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.AttendanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/** Attendance (check-in / check-out). Every signed-in person; the service limits whose days they see. */
@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    @Checkpoint({Menus.ATT_CHECKIN})
    @GetMapping("/today")
    public AttendanceToday today(@AuthenticationPrincipal AuthUser me) {
        return attendanceService.today(me);
    }

    /** Multipart: "photo" (the live camera picture) plus the CheckInRequest fields. */
    @Checkpoint({Menus.ATT_CHECKIN})
    @PostMapping(path = "/check-in", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceRecord checkIn(@AuthenticationPrincipal AuthUser me, @Valid @ModelAttribute CheckInRequest request,
                                    @RequestPart(name = "photo", required = false) MultipartFile photo,
                                    HttpServletRequest http) {
        return attendanceService.checkIn(me, request, bytes(photo), clientIp(http));
    }

    /** Closes the open entry: "End work today", or the current field visit. */
    @Checkpoint({Menus.ATT_CHECKIN})
    @PostMapping("/check-out")
    public AttendanceRecord checkOut(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CheckOutRequest request,
                                     HttpServletRequest http) {
        return attendanceService.checkOut(me, request, clientIp(http));
    }

    /** My days, newest first, with their entries (default: the last 30 days). */
    @Checkpoint({Menus.ATT_CHECKIN})
    @GetMapping("/me")
    public List<AttendanceDaySummary> mine(@AuthenticationPrincipal AuthUser me,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.days(me, null, from, to);
    }

    /** People whose attendance I may look at. */
    @Checkpoint({Menus.ATT_TEAM})
    @GetMapping("/team")
    public List<TeamMember> team(@AuthenticationPrincipal AuthUser me) {
        return attendanceService.team(me);
    }

    /** My team on one day (default: today). */
    @Checkpoint({Menus.ATT_TEAM})
    @GetMapping("/day")
    public AttendanceDay day(@AuthenticationPrincipal AuthUser me,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendanceService.day(me, date);
    }

    /** One person's days, newest first, with their entries. */
    @Checkpoint({Menus.ATT_TEAM})
    @GetMapping("/employees/{employeeId}")
    public List<AttendanceDaySummary> employeeDays(@AuthenticationPrincipal AuthUser me, @PathVariable Long employeeId,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.days(me, employeeId, from, to);
    }

    @Checkpoint({Menus.ATT_CHECKIN, Menus.ATT_TEAM})
    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(attendanceService.photo(me, id));
    }

    private static byte[] bytes(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            throw ApiException.badRequest("Take a photo to check in");
        }
        try {
            return photo.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The photo could not be read; please take it again");
        }
    }

    /**
     * The caller's address: the first X-Forwarded-For entry when behind a proxy (Render sets it), else the
     * connection's address. Kept as a hint for reviewers, not as proof of location.
     */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = StringUtils.hasText(forwarded) ? forwarded.split(",")[0].strip() : request.getRemoteAddr();
        return ip == null ? null : ip.length() > 64 ? ip.substring(0, 64) : ip;
    }
}
