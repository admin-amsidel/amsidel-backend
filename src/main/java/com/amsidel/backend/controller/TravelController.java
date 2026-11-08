/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.TravelDtos.*;
import com.amsidel.backend.entity.TravelClaim;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.TravelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Travel allowance: rates per km, my claims (typed in or GPS trips), and the team's claims to approve and settle. */
@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelController {

    private final TravelService service;

    @Checkpoint({Menus.TA_RATES})
    @GetMapping("/rates")
    public List<RateRow> rates(@AuthenticationPrincipal AuthUser me) {
        return service.rates(me);
    }

    @Checkpoint({Menus.TA_RATES})
    @PutMapping("/rates/{employeeId}")
    public RateRow setRate(@AuthenticationPrincipal AuthUser me, @PathVariable Long employeeId, @Valid @RequestBody RateBody body) {
        return service.setRate(me, employeeId, body);
    }

    /** Also for the Overview's travel section. */
    @Checkpoint({Menus.TA_MY, Menus.OVERVIEW_TRAVEL})
    @GetMapping("/me")
    public MyTravel mine(@AuthenticationPrincipal AuthUser me,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(required = false) TravelClaim.Status status) {
        return service.mine(me, from, to, status);
    }

    @Checkpoint({Menus.TA_MY})
    @GetMapping("/customers")
    public List<CustomerOption> customers(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) String q) {
        return service.customers(me, q);
    }

    @Checkpoint({Menus.TA_MY})
    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView addManual(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ManualBody body) {
        return service.addManual(me, body);
    }

    @Checkpoint({Menus.TA_MY})
    @PostMapping("/trips")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView startTrip(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody StartBody body) {
        return service.startTrip(me, body);
    }

    @Checkpoint({Menus.TA_MY})
    @PostMapping("/trips/{id}/stops")
    public ClaimView addStop(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody StopBody body) {
        return service.addStop(me, id, body);
    }

    @Checkpoint({Menus.TA_MY})
    @PostMapping("/trips/{id}/complete")
    public ClaimView complete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody CompleteBody body) {
        return service.completeTrip(me, id, body);
    }

    @Checkpoint({Menus.TA_MY})
    @PostMapping("/trips/{id}/confirm")
    public ClaimView confirm(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody ConfirmBody body) {
        return service.confirmTrip(me, id, body);
    }

    @Checkpoint({Menus.TA_MY, Menus.TA_TEAM})
    @PutMapping("/claims/{id}/stops/{sequence}")
    public ClaimView editStop(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @PathVariable int sequence, @Valid @RequestBody StopEdit body) {
        return service.editStop(me, id, sequence, body);
    }

    @Checkpoint({Menus.TA_MY})
    @DeleteMapping("/claims/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        service.delete(me, id);
        return ResponseEntity.noContent().build();
    }

    /** Also for the Overview's travel section, in its Team view. */
    @Checkpoint({Menus.TA_TEAM})
    @GetMapping("/team")
    public TeamTravel team(@AuthenticationPrincipal AuthUser me,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) TravelClaim.Status status,
                           @RequestParam(required = false) Long employeeId,
                           @RequestParam(required = false) Long brandId) {
        return service.team(me, from, to, status, employeeId, brandId);
    }

    @Checkpoint({Menus.TA_TEAM})
    @PutMapping("/claims/{id}/decision")
    public ClaimView decide(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody DecideBody body) {
        return service.decide(me, id, body);
    }

    @Checkpoint({Menus.TA_TEAM})
    @PutMapping("/claims/{id}/waiting")
    public ClaimView waiting(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.markWaiting(me, id);
    }

    @Checkpoint({Menus.TA_TEAM})
    @PutMapping("/claims/{id}/settlement")
    public ClaimView settle(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody SettleBody body) {
        return service.settle(me, id, body);
    }
}
