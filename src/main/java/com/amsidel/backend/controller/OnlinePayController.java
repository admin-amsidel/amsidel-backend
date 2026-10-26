package com.amsidel.backend.controller;

import com.amsidel.backend.dto.OnlinePayDtos.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Checkpoint;
import com.amsidel.backend.security.Menus;
import com.amsidel.backend.service.OnlinePayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Pay bill (Product portal): bills paid online by card or UPI through the brand's Razorpay account. */
@RestController
@RequestMapping("/api/trade/pay")
@RequiredArgsConstructor
@Checkpoint({Menus.PROD_PAY})
public class OnlinePayController {

    private final OnlinePayService pay;

    /** The processing fees and the brands, with whether each can take payments yet. */
    @GetMapping("/setup")
    public PaySetup setup(@AuthenticationPrincipal AuthUser me) {
        return pay.setup(me);
    }

    /** Owners and admins: the processing fee for cards or UPI, in percent. */
    @PutMapping("/fees")
    public FeeView setFee(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody FeeBody body) {
        return pay.setFee(me, body);
    }

    /** Customers with bills still to pay. */
    @GetMapping("/customers")
    public List<CustomerDue> customers(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId) {
        return pay.customers(me, brandId);
    }

    /** A customer's bills with money still due. */
    @GetMapping("/bills")
    public List<OpenBill> bills(@AuthenticationPrincipal AuthUser me, @RequestParam Long customerId) {
        return pay.bills(me, customerId);
    }

    /** Makes the Razorpay order (amount + processing fee) for the payment window. */
    @PostMapping("/orders")
    public Checkout start(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody StartBody body) {
        return pay.start(me, body);
    }

    @PostMapping("/orders/{id}/verify")
    public OnlineView verify(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody VerifyBody body) {
        return pay.verify(me, id, body);
    }

    @PostMapping("/orders/{id}/fail")
    public OnlineView fail(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody FailBody body) {
        return pay.fail(me, id, body);
    }

    /** Asks Razorpay what became of the order; records it when it was paid. */
    @PostMapping("/orders/{id}/refresh")
    public OnlineView refresh(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return pay.refresh(me, id);
    }

    /** Manual UPI: the brand's UPI QR code for this payment (amount filled in), as a picture. */
    @GetMapping("/orders/{id}/qr")
    public org.springframework.http.ResponseEntity<byte[]> qr(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        byte[] img = pay.manualQr(me, id);
        String type = img.length > 3 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8 ? "image/jpeg" : "image/png";
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(type))
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(img);
    }

    /** Manual UPI: the customer paid at the office; the staff record the UPI reference. */
    @PostMapping("/orders/{id}/manual")
    public OnlineView confirmManual(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody ManualBody body) {
        return pay.confirmManual(me, id, body);
    }

    @PostMapping("/orders/{id}/refund")
    public OnlineView refund(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody RefundBody body) {
        return pay.refund(me, id, body);
    }

    @GetMapping("/history")
    public List<OnlineView> history(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pay.history(me, brandId, from, to);
    }

    /** Where the money came from (card or UPI ID), for refunds. */
    @GetMapping("/sources")
    public List<SourceView> sources(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Long brandId,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pay.sources(me, brandId, from, to);
    }
}
