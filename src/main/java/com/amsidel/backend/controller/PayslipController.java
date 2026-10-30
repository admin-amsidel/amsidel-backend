/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.PayslipDtos.*;
import com.amsidel.backend.repository.BrandLogoRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.PayslipPdf;
import com.amsidel.backend.service.PayslipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Monthly payslips. Payroll (the Monthly payslips menu) makes drafts from the salaries, checks and publishes them;
 * every employee (the My pay menu) sees their own published payslips, their salary and their pay for the year.
 */
@RestController
@RequiredArgsConstructor
public class PayslipController {

    private final PayslipService service;
    private final PayslipPdf pdf;
    private final BrandLogoRepository logos;

    // ---- payroll ----

    @Checkpoint({Menus.PAYSLIPS})
    @GetMapping("/api/payroll/payslips")
    public MonthView month(@AuthenticationPrincipal AuthUser me, @RequestParam int year, @RequestParam int month) {
        return service.month(me, year, month);
    }

    @Checkpoint({Menus.PAYSLIPS})
    @PostMapping("/api/payroll/payslips/generate")
    public GenerateResult generate(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody Generate body) {
        return service.generate(me, body);
    }

    @Checkpoint({Menus.PAYSLIPS})
    @GetMapping("/api/payroll/payslips/{id}")
    public PayslipView payslip(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.payslip(me, id);
    }

    /** Save as a draft, or publish (publish = true). */
    @Checkpoint({Menus.PAYSLIPS})
    @PutMapping("/api/payroll/payslips/{id}")
    public PayslipView save(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody Save body) {
        return service.save(me, id, body);
    }

    /** This month's income tax for the amounts being typed (nothing saved); empty for a Manual tax salary. */
    @Checkpoint({Menus.PAYSLIPS})
    @PostMapping("/api/payroll/payslips/{id}/tax")
    public PayslipTax tax(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody TaxPreview body) {
        return service.taxPreview(me, id, body);
    }

    @Checkpoint({Menus.PAYSLIPS})
    @PostMapping("/api/payroll/payslips/{id}/recalculate")
    public PayslipView recalculate(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.recalculate(me, id);
    }

    @Checkpoint({Menus.PAYSLIPS})
    @PostMapping("/api/payroll/payslips/publish")
    public Map<String, Integer> publish(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody Ids body) {
        return Map.of("published", service.publish(me, body.getIds()));
    }

    /** The salary was credited (date, bank / cash / UPI, reference): the employee now sees the payslip. */
    @Checkpoint({Menus.PAYSLIPS})
    @PutMapping("/api/payroll/payslips/{id}/payment")
    public PayslipView markPaid(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody Payment body) {
        return service.markPaid(me, id, body);
    }

    @Checkpoint({Menus.PAYSLIPS})
    @DeleteMapping("/api/payroll/payslips/{id}/payment")
    public PayslipView unmarkPaid(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.unmarkPaid(me, id);
    }

    @Checkpoint({Menus.PAYSLIPS})
    @PostMapping("/api/payroll/payslips/payments")
    public Map<String, Integer> markPaid(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody BulkPayment body) {
        return Map.of("credited", service.markPaid(me, body));
    }

    @Checkpoint({Menus.PAYSLIPS})
    @PostMapping("/api/payroll/payslips/{id}/unpublish")
    public PayslipView unpublish(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.unpublish(me, id);
    }

    @Checkpoint({Menus.PAYSLIPS})
    @DeleteMapping("/api/payroll/payslips/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        service.delete(me, id);
    }

    @Checkpoint({Menus.PAYSLIPS})
    @GetMapping("/api/payroll/payslips/{id}/pdf")
    public ResponseEntity<byte[]> payslipPdf(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return asPdf(service.payslip(me, id));
    }

    // ---- the employee ----

    /** Own salary, published payslips and pay for the year (this financial year when fy is left out). */
    @Checkpoint({Menus.MY_PAY})
    @GetMapping("/api/my-pay")
    public MyPay myPay(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Integer fy) {
        return service.myPay(me, fy);
    }

    @Checkpoint({Menus.MY_PAY})
    @GetMapping("/api/my-pay/payslips/{id}")
    public PayslipView myPayslip(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.myPayslip(me, id);
    }

    @Checkpoint({Menus.MY_PAY})
    @GetMapping("/api/my-pay/payslips/{id}/pdf")
    public ResponseEntity<byte[]> myPayslipPdf(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return asPdf(service.myPayslip(me, id));
    }

    /** The brand's logo, or null for the default. */
    private byte[] logo(PayslipView p) {
        Long brandId = p.company().brandId();
        return brandId == null || p.company().logoUpdatedAt() == null ? null
                : logos.findById(brandId).map(l -> l.getContent()).orElse(null);
    }

    private ResponseEntity<byte[]> asPdf(PayslipView p) {
        String name = "Payslip-%d-%02d-%s.pdf".formatted(p.year(), p.month(), p.employee().empNumber());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(pdf.render(p, logo(p)));
    }
}
