/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.ProductItemRepository;
import com.amsidel.backend.repository.PurchaseBillRepository;
import com.amsidel.backend.repository.TradePaymentRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.amsidel.backend.service.CatalogService.blank;

/**
 * Purchase bills: goods bought from a supplier, numbered DWPUR-… when made. A draft can be changed and paid in
 * advance; receiving it adds its items to stock and (by default) makes each line's price the item's supplier price
 * and the supplier its usual one when it has none. A received bill is cancelled, not changed: its items leave stock
 * again, after any payments are refunded.
 */
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseBillRepository billRepository;
    private final ProductItemRepository itemRepository;
    private final TradePaymentRepository paymentRepository;
    private final PartyService parties;
    private final StockLedger ledger;
    private final TradeNumbers numbers;
    private final TradePayments payments;
    private final TradeAccess access;
    private final TradeHistoryLog history;

    @Transactional(readOnly = true)
    public List<PurchaseRow> list(AuthUser me, Long brandId, LocalDate from, LocalDate to) {
        canRead(me);
        return billRepository.list(access.scope(me, brandId), from, to).stream().map(PurchaseService::row).toList();
    }

    @Transactional(readOnly = true)
    public List<PurchaseRow> forSupplier(AuthUser me, Long supplierId) {
        parties.supplierFull(me, supplierId);
        return billRepository.forSupplier(supplierId).stream().map(PurchaseService::row).toList();
    }

    @Transactional(readOnly = true)
    public PurchaseView get(AuthUser me, Long id) {
        canRead(me);
        return view(full(me, id));
    }

    @Transactional
    public PurchaseView create(AuthUser me, PurchaseBody body) {
        canWrite(me);
        PurchaseBill b = new PurchaseBill();
        b.setBrand(access.brandFor(me, body.getBrandId()));
        b.setStatus(PurchaseStatus.DRAFT);
        b.setCreatedBy(access.me(me));
        b.setUpdatedBy(access.me(me));
        fill(me, b, body);
        b.setNumber(numbers.next(b.getBrand(), TradeNumbers.PURCHASE, b.getBillDate()));
        billRepository.saveAndFlush(b);
        log(me, b, "CREATED", "From " + b.getSupplier().getName() + (b.getSupplierBillNo() == null ? "" : " (their bill " + b.getSupplierBillNo() + ")")
                + ": " + linesText(b) + "; total " + PayslipPdf.rupees(b.getTotal()));
        return view(b);
    }

    @Transactional
    public PurchaseView update(AuthUser me, Long id, PurchaseBody body) {
        canWrite(me);
        PurchaseBill b = full(me, id);
        if (b.getStatus() != PurchaseStatus.DRAFT) {
            throw ApiException.conflict("A received bill cannot be changed; cancel it and make a new one");
        }
        String linesBefore = linesText(b);
        BigDecimal totalBefore = b.getTotal();
        String supplierBefore = b.getSupplier().getName();
        String billNoBefore = b.getSupplierBillNo();
        LocalDate dateBefore = b.getBillDate();
        fill(me, b, body);
        TradeHistoryLog.Changes changes = new TradeHistoryLog.Changes().add("Supplier", supplierBefore, b.getSupplier().getName())
                .add("Their bill no", billNoBefore, b.getSupplierBillNo()).add("Bill date", dateBefore, b.getBillDate());
        if (!linesBefore.equals(linesText(b))) {
            changes.note("Items now: " + linesText(b));
        }
        changes.add("Total", totalBefore, b.getTotal());
        b.setUpdatedBy(access.me(me));
        billRepository.saveAndFlush(b);
        if (!changes.isEmpty()) {
            log(me, b, "UPDATED", changes.toString());
        }
        return view(b);
    }

    /** Stock in; the prices paid become the items' supplier prices (unless told not to). */
    @Transactional
    public PurchaseView receive(AuthUser me, Long id, boolean updatePrices) {
        canWrite(me);
        PurchaseBill b = full(me, id);
        if (b.getStatus() != PurchaseStatus.DRAFT) {
            throw ApiException.conflict("Only a draft bill can be received");
        }
        for (PurchaseBillLine l : b.getLines()) {
            ProductItem item = l.getItem();
            if (updatePrices) {
                item.setSupplierPrice(l.getRate());
            }
            if (item.getSupplier() == null) {
                item.setSupplier(b.getSupplier());
            }
            ledger.move(item.getId(), StockMoveKind.PURCHASE, l.getQuantity(), b.getBillDate(), l.getRate(), b, null,
                    "Bought: " + b.getNumber() + (b.getSupplierBillNo() == null ? "" : " (supplier bill " + b.getSupplierBillNo() + ")"),
                    access.me(me), false);
        }
        b.setStatus(PurchaseStatus.RECEIVED);
        b.setReceivedAt(Instant.now());
        b.setReceivedBy(access.me(me));
        b.setUpdatedBy(access.me(me));
        billRepository.saveAndFlush(b);
        log(me, b, "RECEIVED", "Into stock: " + linesText(b) + (updatePrices ? "; supplier prices updated" : ""));
        return view(b);
    }

    /** A received bill: its items leave stock again (stock may go below zero if they were sold). */
    @Transactional
    public PurchaseView cancel(AuthUser me, Long id, String reason) {
        canWrite(me);
        PurchaseBill b = full(me, id);
        if (b.getStatus() != PurchaseStatus.RECEIVED) {
            throw ApiException.conflict(b.getStatus() == PurchaseStatus.DRAFT ? "A draft is deleted, not cancelled"
                    : "This bill is already cancelled");
        }
        if (b.getPaid().signum() > 0) {
            throw ApiException.conflict("Record the supplier's refund of " + PayslipPdf.rupees(b.getPaid()) + " first, then cancel");
        }
        for (PurchaseBillLine l : b.getLines()) {
            ledger.move(l.getItem().getId(), StockMoveKind.PURCHASE_CANCEL, l.getQuantity().negate(), access.today(), l.getRate(),
                    b, null, "Cancelled: " + b.getNumber(), access.me(me), true);
        }
        b.setStatus(PurchaseStatus.CANCELLED);
        b.setCancelledAt(Instant.now());
        b.setCancelReason(reason.strip());
        b.setUpdatedBy(access.me(me));
        billRepository.saveAndFlush(b);
        log(me, b, "CANCELLED", reason.strip() + "; items out of stock again");
        return view(b);
    }

    @Transactional
    public void delete(AuthUser me, Long id) {
        canWrite(me);
        PurchaseBill b = full(me, id);
        if (b.getStatus() != PurchaseStatus.DRAFT) {
            throw ApiException.conflict("A received bill is cancelled, not deleted");
        }
        if (!paymentRepository.forBill(id).isEmpty()) {
            throw ApiException.conflict("This bill has payments; void them first");
        }
        log(me, b, "DELETED", "From " + b.getSupplier().getName() + ", total " + PayslipPdf.rupees(b.getTotal()));
        billRepository.delete(b);
    }

    @Transactional
    public PurchaseView addPayment(AuthUser me, Long id, PaymentBody body) {
        canWrite(me);
        PurchaseBill b = full(me, id);
        if (b.getStatus() == PurchaseStatus.CANCELLED) {
            throw ApiException.conflict("This bill is cancelled");
        }
        TradePayment p = payments.record(me, b.getBrand(), TradeParty.SUPPLIER, b.getTotal(), b.getPaid(), body, x -> {
            x.setPurchaseBill(b);
            x.setSupplier(b.getSupplier());
        });
        b.setPaid(TradePayments.paid(paymentRepository.forBill(id)));
        b.setUpdatedBy(access.me(me));
        billRepository.saveAndFlush(b);
        log(me, b, p.getKind() == TradePaymentKind.REFUND ? "REFUND" : "PAYMENT", TradePayments.describe(p) + "; paid now " + PayslipPdf.rupees(b.getPaid()));
        return view(b);
    }

    @Transactional
    public PurchaseView voidPayment(AuthUser me, TradePayment p, String reason) {
        canWrite(me);
        PurchaseBill b = full(me, p.getPurchaseBill().getId());
        payments.voidIt(me, p, reason);
        BigDecimal paid = TradePayments.paid(paymentRepository.forBill(b.getId()));
        if (paid.signum() < 0) {
            throw ApiException.conflict("Void the refund first: more would be refunded than paid");
        }
        b.setPaid(paid);
        b.setUpdatedBy(access.me(me));
        billRepository.saveAndFlush(b);
        log(me, b, "VOIDED", TradePayments.describe(p) + " voided: " + reason.strip());
        return view(b);
    }

    private void fill(AuthUser me, PurchaseBill b, PurchaseBody body) {
        Supplier s = parties.supplierFull(me, body.getSupplierId());
        if (!s.getBrand().getId().equals(b.getBrand().getId())) {
            throw ApiException.badRequest("Choose a supplier of " + b.getBrand().getName());
        }
        b.setSupplier(s);
        b.setSupplierBillNo(blank(body.getSupplierBillNo()));
        b.setBillDate(body.getBillDate());
        b.getLines().clear();
        int n = 0;
        for (PurchaseLineBody lb : body.getLines()) {
            ProductItem item = itemRepository.findFull(lb.getItemId())
                    .filter(i -> i.getBrand().getId().equals(b.getBrand().getId()))
                    .orElseThrow(() -> ApiException.badRequest("Choose items of " + b.getBrand().getName()));
            PurchaseBillLine l = new PurchaseBillLine();
            l.setBill(b);
            l.setItem(item);
            l.setItemCode(item.getCode());
            l.setName(item.getName());
            l.setQuantity(lb.getQuantity().setScale(2, RoundingMode.HALF_UP));
            l.setRate(lb.getRate().setScale(2, RoundingMode.HALF_UP));
            l.setAmount(TradeMoney.amount(l.getQuantity(), l.getRate()));
            l.setSortOrder(n++);
            b.getLines().add(l);
        }
        TradeMoney m = TradeMoney.of(b.getLines().stream().map(PurchaseBillLine::getAmount).toList(), body.getDiscount(),
                body.getMiscAmount(), body.getGstRate());
        b.setSubtotal(m.subtotal());
        b.setDiscount(m.discount());
        b.setMiscLabel(blank(body.getMiscLabel()));
        b.setMiscAmount(m.miscAmount());
        b.setTaxable(m.taxable());
        b.setGstRate(m.gstRate());
        b.setInterState(body.isInterState());
        b.setGstAmount(m.gstAmount());
        b.setRoundOff(m.roundOff());
        b.setTotal(m.total());
        b.setRemarks(blank(body.getRemarks()));
        if (b.getPaid().compareTo(b.getTotal()) > 0) {
            throw ApiException.badRequest("More than this total is already paid (" + PayslipPdf.rupees(b.getPaid()) + ")");
        }
    }

    private void log(AuthUser me, PurchaseBill b, String action, String details) {
        history.record(me, b.getBrand(), TradeSubject.PURCHASE, b.getId(), "Purchase " + b.getNumber(), action, details);
    }

    /** "10 x UPVC-SW-01 @ ₹1,200.00, …". */
    private static String linesText(PurchaseBill b) {
        return b.getLines().stream().map(l -> StockLedger.plain(l.getQuantity()) + " x " + l.getItemCode() + " @ " + PayslipPdf.rupees(l.getRate()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    PurchaseBill full(AuthUser me, Long id) {
        PurchaseBill b = billRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Purchase bill not found: " + id));
        access.check(me, b.getBrand());
        return b;
    }

    private void canRead(AuthUser me) {
        access.require(me, "You cannot see purchase bills", Menus.PROD_PURCHASES, Menus.PROD_SUPPLIERS, Menus.PROD_REPORTS);
    }

    private void canWrite(AuthUser me) {
        access.require(me, "You cannot change purchase bills", Menus.PROD_PURCHASES);
    }

    static String payStatus(PurchaseBill b) {
        if (b.getStatus() == PurchaseStatus.CANCELLED || (b.getStatus() == PurchaseStatus.DRAFT && b.getPaid().signum() == 0)) {
            return null;
        }
        return TradeMoney.payStatus(b.getTotal(), b.getPaid());
    }

    static PurchaseRow row(PurchaseBill b) {
        BigDecimal due = b.getStatus() == PurchaseStatus.RECEIVED ? b.getTotal().subtract(b.getPaid()).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        return new PurchaseRow(b.getId(), b.getNumber(), b.getStatus(), payStatus(b), b.getBrand().getCode(), b.getSupplier().getId(),
                b.getSupplier().getName(), b.getSupplierBillNo(), b.getBillDate(), b.getTotal(), b.getPaid(), due, b.getLines().size(),
                b.getCreatedBy() == null ? null : b.getCreatedBy().getName());
    }

    private PurchaseView view(PurchaseBill b) {
        Supplier s = b.getSupplier();
        List<PurchaseLineView> lines = b.getLines().stream().map(l -> new PurchaseLineView(l.getId(), l.getItem().getId(),
                l.getItemCode(), l.getName(), l.getItem().getUnit(), l.getQuantity(), l.getRate(), l.getAmount(),
                l.getItem().getSupplierPrice())).toList();
        List<PaymentView> pays = paymentRepository.forBill(b.getId()).stream().map(TradePayments::view).toList();
        return new PurchaseView(b.getId(), b.getNumber(), b.getStatus(), payStatus(b), Company.of(b.getBrand()),
                new PartyRef(s.getId(), s.getCode(), s.getName(), s.getPhone(), s.getEmail(), s.getAddress(), s.getGstNumber()),
                b.getSupplierBillNo(), b.getBillDate(), lines,
                TradeMoney.totals(b.getSubtotal(), b.getDiscount(), b.getMiscLabel(), b.getMiscAmount(), b.getTaxable(),
                        b.getGstRate(), b.isInterState(), b.getGstAmount(), b.getRoundOff(), b.getTotal(), b.getPaid()),
                b.getRemarks(), pays, b.getCreatedBy() == null ? null : b.getCreatedBy().getName(), b.getCreatedAt(),
                b.getReceivedAt(), b.getReceivedBy() == null ? null : b.getReceivedBy().getName(), b.getCancelledAt(),
                b.getCancelReason(), b.getStatus() == PurchaseStatus.DRAFT, b.getUpdatedBy() == null ? null : b.getUpdatedBy().getName(),
                b.getUpdatedAt(), history.of(List.of(TradeSubject.PURCHASE), b.getId()));
    }
}
