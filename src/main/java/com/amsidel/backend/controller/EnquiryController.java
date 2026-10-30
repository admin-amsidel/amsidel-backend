/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.EnquiryRequest;
import com.amsidel.backend.service.EnquiryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Enquiries from the public website, mailed to the enquiry inbox; no sign-in. */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class EnquiryController {

    private final EnquiryService enquiryService;

    /** 200 as soon as the enquiry is checked (it is mailed in the background); 503 when mail is not set up; 429 when too many. */
    @PostMapping("/enquiry")
    public Map<String, Boolean> enquiry(@Valid @RequestBody EnquiryRequest request, HttpServletRequest http) {
        enquiryService.send(request, AttendanceController.clientIp(http));
        return Map.of("sent", true);
    }
}
