package com.amsidel.backend.controller;

import com.amsidel.backend.dto.*;
import com.amsidel.backend.entity.LeaveStatus;
import com.amsidel.backend.entity.LeaveType;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.LeaveService;
import com.amsidel.backend.service.LeaveSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Leave tracking. Each API needs its menu (@Checkpoint): /me = My leave; changing types and holidays = Leave
 * settings; approvals, grants and others' balances = Approvals, with person-by-person checks in LeaveService.
 */
@RestController
@RequestMapping("/api/leave")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;
    private final LeaveSettingsService settingsService;

    // ---- settings ----

    @Checkpoint({Menus.LEAVE_MY, Menus.LEAVE_APPROVALS, Menus.LEAVE_SETTINGS})

    @GetMapping("/types")
    public List<LeaveType> types() {
        return settingsService.types();
    }

    @Checkpoint({Menus.LEAVE_SETTINGS})

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveType createType(@Valid @RequestBody LeaveTypeRequest request) {
        return settingsService.createType(request);
    }

    @Checkpoint({Menus.LEAVE_SETTINGS})

    @PutMapping("/types/{id}")
    public LeaveType updateType(@PathVariable Long id, @Valid @RequestBody LeaveTypeRequest request) {
        return settingsService.updateType(id, request);
    }

    @Checkpoint({Menus.LEAVE_MY, Menus.LEAVE_APPROVALS, Menus.LEAVE_SETTINGS})

    @GetMapping("/restricted-holidays")
    public List<RestrictedHolidayResponse> holidays(@RequestParam(required = false) Integer year) {
        return settingsService.holidays(year != null ? year : LocalDate.now().getYear());
    }

    @Checkpoint({Menus.LEAVE_SETTINGS})

    @PostMapping("/restricted-holidays")
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictedHolidayResponse addHoliday(@Valid @RequestBody RestrictedHolidayRequest request) {
        return settingsService.addHoliday(request);
    }

    @Checkpoint({Menus.LEAVE_SETTINGS})

    @DeleteMapping("/restricted-holidays/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeHoliday(@PathVariable Long id) {
        settingsService.removeHoliday(id);
    }

    // ---- my leave ----

    @Checkpoint({Menus.LEAVE_MY})

    @GetMapping("/me/balances")
    public List<LeaveBalance> myBalances(@AuthenticationPrincipal AuthUser me,
                                         @RequestParam(required = false) Integer year) {
        return leaveService.myBalances(me, year);
    }

    @Checkpoint({Menus.LEAVE_MY})

    @GetMapping("/me/requests")
    public List<LeaveRequestResponse> myRequests(@AuthenticationPrincipal AuthUser me,
                                                 @RequestParam(required = false) Integer year) {
        return leaveService.myRequests(me, year);
    }

    @Checkpoint({Menus.LEAVE_MY})

    @PostMapping("/me/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequestResponse apply(@AuthenticationPrincipal AuthUser me,
                                      @Valid @RequestBody LeaveApplyRequest request) {
        return leaveService.apply(me, request);
    }

    @Checkpoint({Menus.LEAVE_MY})

    @PostMapping("/me/requests/{id}/cancel")
    public LeaveRequestResponse cancel(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return leaveService.cancel(me, id);
    }

    @Checkpoint({Menus.LEAVE_MY})

    @GetMapping("/me/grants")
    public List<LeaveGrantResponse> myGrants(@AuthenticationPrincipal AuthUser me,
                                             @RequestParam(required = false) Integer year) {
        return leaveService.myGrants(me, year);
    }

    // ---- approvers ----

    @Checkpoint({Menus.LEAVE_APPROVALS})

    @GetMapping("/requests")
    public List<LeaveRequestResponse> requests(@AuthenticationPrincipal AuthUser me,
                                               @RequestParam(required = false) LeaveStatus status,
                                               @RequestParam(required = false) Long brandId) {
        return leaveService.requestsToDecide(me, status, brandId);
    }

    @Checkpoint({Menus.LEAVE_APPROVALS})

    @PostMapping("/requests/{id}/approve")
    public LeaveRequestResponse approve(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                        @Valid @RequestBody(required = false) LeaveDecisionRequest request) {
        return leaveService.decide(me, id, true, request == null ? null : request.getNote());
    }

    @Checkpoint({Menus.LEAVE_APPROVALS})

    @PostMapping("/requests/{id}/reject")
    public LeaveRequestResponse reject(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                       @Valid @RequestBody LeaveDecisionRequest request) {
        return leaveService.decide(me, id, false, request.getNote());
    }

    @Checkpoint({Menus.LEAVE_APPROVALS})

    @GetMapping("/team")
    public List<TeamMember> team(@AuthenticationPrincipal AuthUser me) {
        return leaveService.team(me);
    }

    @Checkpoint({Menus.LEAVE_APPROVALS})

    @GetMapping("/balances")
    public List<LeaveBalance> balances(@AuthenticationPrincipal AuthUser me, @RequestParam Long employeeId,
                                       @RequestParam(required = false) Integer year) {
        return leaveService.balancesOf(me, employeeId, year);
    }

    @Checkpoint({Menus.LEAVE_APPROVALS})

    @GetMapping("/grants")
    public List<LeaveGrantResponse> grants(@AuthenticationPrincipal AuthUser me, @RequestParam Long employeeId,
                                           @RequestParam(required = false) Integer year) {
        return leaveService.grantsOf(me, employeeId, year);
    }

    @Checkpoint({Menus.LEAVE_APPROVALS})

    @PostMapping("/grants")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveGrantResponse grant(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody LeaveGrantRequest request) {
        return leaveService.grant(me, request);
    }
}
