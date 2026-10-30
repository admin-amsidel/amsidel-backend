/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.AccountDtos.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.BankService;
import com.amsidel.backend.service.MoneyEntryService;
import com.amsidel.backend.service.ReconcileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * The product portal's accounts (/api/accounts): other income and expenses (Income & expenses), and the P&L
 * reconciler with bank statements and balances. Owners and admins delete; the services check the menu and brand.
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Checkpoint({Menus.PROD_LEDGER, Menus.PROD_RECONCILE})
public class AccountsController {

    /** Bank statements: up to 10 MB. */
    private static final long MAX_FILE = 10L * 1024 * 1024;

    private final MoneyEntryService entries;
    private final com.amsidel.backend.service.MoneyTypeService types;
    private final BankService bank;
    private final ReconcileService reconcile;

    // ---- other income and expenses ----

    @GetMapping("/entries")
    public List<EntryView> entries(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return entries.list(me, brandId, from, to);
    }

    @GetMapping("/entries/{id}")
    public EntryView entry(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return entries.get(me, id);
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryView addEntry(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody EntryBody body) {
        return entries.create(me, body);
    }

    @PutMapping("/entries/{id}")
    public EntryView saveEntry(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody EntryBody body) {
        return entries.update(me, id, body);
    }

    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEntry(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        entries.delete(me, id);
    }

    /** The invoices, projects, purchase bills and suppliers of a brand an entry can belong to. */
    // ---- categories (owners and admins change them) ----

    @GetMapping("/categories")
    public List<com.amsidel.backend.dto.AccountDtos.TypeView> categories(@AuthenticationPrincipal AuthUser me) {
        return types.list(me);
    }

    @PostMapping("/categories")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public com.amsidel.backend.dto.AccountDtos.TypeView addCategory(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody com.amsidel.backend.dto.AccountDtos.TypeBody body) {
        return types.create(me, body);
    }

    @PutMapping("/categories/{code}")
    public com.amsidel.backend.dto.AccountDtos.TypeView saveCategory(@AuthenticationPrincipal AuthUser me, @PathVariable String code, @Valid @RequestBody com.amsidel.backend.dto.AccountDtos.TypeBody body) {
        return types.update(me, code, body);
    }

    @DeleteMapping("/categories/{code}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteCategory(@AuthenticationPrincipal AuthUser me, @PathVariable String code) {
        types.delete(me, code);
    }

    @GetMapping("/links")
    public EntryLinks links(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return entries.links(me, brandId);
    }

    /** What the customer did not pay on an invoice, written off as a discount. */
    @PostMapping("/invoices/{invoiceId}/write-off")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryView writeOff(@AuthenticationPrincipal AuthUser me, @PathVariable Long invoiceId, @Valid @RequestBody WriteOffBody body) {
        return entries.writeOff(me, invoiceId, body);
    }

    // ---- the P&L reconciler ----

    /** groupBy: DAY, WEEK, MONTH or YEAR (financial year). */
    @GetMapping("/reconcile")
    public Report report(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(defaultValue = "MONTH") String groupBy) {
        return reconcile.report(me, brandId, from, to, groupBy);
    }

    @GetMapping("/reconcile/excel")
    public ResponseEntity<byte[]> excel(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(defaultValue = "MONTH") String groupBy) {
        return AttendanceController.excel(reconcile.excel(me, brandId, from, to, groupBy));
    }

    // ---- bank ----

    @GetMapping("/bank/statements")
    public List<StatementView> statements(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return bank.statements(me, brandId);
    }

    /** Multipart: "file" (.xlsx, .xls or .csv), optional brandId and remarks. */
    @PostMapping("/bank/statements")
    @ResponseStatus(HttpStatus.CREATED)
    public StatementView upload(@AuthenticationPrincipal AuthUser me, @RequestParam("file") MultipartFile file,
                                @RequestParam(required = false) Long brandId, @RequestParam(required = false) String remarks) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("Choose the statement file");
        }
        if (file.getSize() > MAX_FILE) {
            throw ApiException.badRequest("The file is larger than 10 MB");
        }
        try {
            return bank.upload(me, brandId, file.getOriginalFilename(), file.getBytes(), remarks);
        } catch (IOException e) {
            throw ApiException.badRequest("The file could not be read");
        }
    }

    @DeleteMapping("/bank/statements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStatement(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        bank.deleteStatement(me, id);
    }

    @GetMapping("/bank/template")
    public ResponseEntity<byte[]> template(@AuthenticationPrincipal AuthUser me) {
        return AttendanceController.excel(bank.template());
    }

    @GetMapping("/bank/balances")
    public List<BalanceView> balances(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return bank.balances(me, brandId);
    }

    @PostMapping("/bank/balances")
    public BalanceView saveBalance(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody BalanceBody body) {
        return bank.saveBalance(me, body);
    }

    @DeleteMapping("/bank/balances/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBalance(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        bank.deleteBalance(me, id);
    }
}
