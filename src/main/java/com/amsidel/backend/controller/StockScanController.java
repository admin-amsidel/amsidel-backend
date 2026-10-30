/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.amsidel.backend.dto.StockScanDtos.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.service.StockScanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Item QR labels (text and printable sheets) and stock received by scanning them, with its approval. */
@RestController
@RequestMapping("/api/trade")
@RequiredArgsConstructor
public class StockScanController {

    private final StockScanService service;

    /** What an item's QR label holds. */
    @GetMapping("/items/{id}/qr")
    public QrView qr(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.qr(me, id);
    }

    /** A sheet of QR labels (A4 PDF): each item as many times as asked. */
    @PostMapping("/items/qr-labels")
    public ResponseEntity<byte[]> labels(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody LabelsBody body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("Item-QR-labels.pdf").build().toString())
                .cacheControl(CacheControl.noStore())
                .body(service.labels(me, body));
    }

    @GetMapping("/stock-receipts")
    public List<ReceiptView> receipts(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return service.list(me, brandId);
    }

    @PostMapping("/stock-receipts")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptView submit(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ReceiptBody body) {
        return service.submit(me, body);
    }

    @PostMapping("/stock-receipts/{id}/approve")
    public ReceiptView approve(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody ApproveBody body) {
        return service.approve(me, id, body);
    }

    @PostMapping("/stock-receipts/{id}/reject")
    public ReceiptView reject(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody RejectBody body) {
        return service.reject(me, id, body);
    }
}
