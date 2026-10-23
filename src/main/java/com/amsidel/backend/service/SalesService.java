package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.ProductItemRepository;
import com.amsidel.backend.repository.SalesDocRepository;
import com.amsidel.backend.repository.TradePaymentRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.amsidel.backend.service.CatalogService.blank;

/**
 * Quotations and invoices. A quotation gets its number (DWQTN-…) when it is made and can be changed until it is
 * accepted or rejected; "make invoice" copies it into a draft invoice. A draft invoice can be changed and can take
 * advance payments; issuing it gives it its number (DWSLD-…) and takes its items out of stock (refused when not
 * enough). An issued invoice is never changed: it is cancelled (stock back, after any payments are refunded).
 */
@Service
@RequiredArgsConstructor
public class SalesService {

    private final SalesDocRepository docRepository;
    private final ProductItemRepository itemRepository;
    private final TradePaymentRepository paymentRepository;
    private final PartyService parties;
    private final StockLedger ledger;
    private final TradeNumbers numbers;
    private final TradePayments payments;
    private final TradeAccess access;

    // ---- reading ----

    @Transactional(readOnly = true)
    public List<SalesRow> list(AuthUser me, SalesDocKind kind, Long brandId, LocalDate from, LocalDate to) {
        canRead(me, kind);
        return docRepository.list(access.scope(me, brandId), kind, from, to).stream().map(SalesService::row).toList();
    }

    @Transactional(readOnly = true)
    public List<SalesRow> forCustomer(AuthUser me, Long customerId) {
        parties.customerFull(me, customerId);
        return docRepository.forCustomer(customerId).stream().map(SalesService::row).toList();
    }

    @Transactional(readOnly = true)
    public SalesView get(AuthUser me, Long id) {
        SalesDoc d = full(me, id);
        canRead(me, d.getKind());
        return view(d);
    }

    // ---- writing ----

    @Transactional
    public SalesView create(AuthUser me, SalesBody body) {
        canWrite(me, body.getKind());
        SalesDoc d = new SalesDoc();
        d.setKind(body.getKind());
        d.setBrand(access.brandFor(me, body.getBrandId()));
        d.setStatus(SalesDocStatus.DRAFT);
        d.setCreatedBy(access.me(me));
        fill(me, d, body);
        if (d.getKind() == SalesDocKind.QUOTATION) {
            d.setNumber(numbers.next(d.getBrand(), TradeNumbers.QUOTATION, d.getDocDate()));
        }
        return view(docRepository.saveAndFlush(d));
    }

    @Transactional
    public SalesView update(AuthUser me, Long id, SalesBody body) {
        SalesDoc d = full(me, id);
        canWrite(me, d.getKind());
        if (!editable(d)) {
            throw ApiException.conflict(d.getKind() == SalesDocKind.INVOICE
                    ? "An issued invoice cannot be changed; cancel it and make a new one"
                    : "This quotation is " + d.getStatus().name().toLowerCase() + "; it cannot be changed");
        }
        if (body.getKind() != d.getKind()) {
            throw ApiException.badRequest("A quotation stays a quotation; use \"Make invoice\"");
        }
        fill(me, d, body);
        return view(docRepository.saveAndFlush(d));
    }

    /** Quotations: SENT, ACCEPTED, REJECTED, or back to DRAFT. */
    @Transactional
    public SalesView setStatus(AuthUser me, Long id, SalesDocStatus status) {
        SalesDoc d = full(me, id);
        canWrite(me, d.getKind());
        if (d.getKind() != SalesDocKind.QUOTATION || status == SalesDocStatus.ISSUED || status == SalesDocStatus.CANCELLED) {
            throw ApiException.badRequest("Invoices are issued or cancelled with their own buttons");
        }
        if (!docRepository.fromQuotation(id).isEmpty() && status != SalesDocStatus.ACCEPTED) {
            throw ApiException.conflict("An invoice was made from this quotation; it stays accepted");
        }
        d.setStatus(status);
        return view(docRepository.saveAndFlush(d));
    }

    /** A draft invoice from a quotation (which becomes accepted). */
    @Transactional
    public SalesView makeInvoice(AuthUser me, Long quotationId) {
        SalesDoc q = full(me, quotationId);
        if (q.getKind() != SalesDocKind.QUOTATION) {
            throw ApiException.badRequest("Only a quotation can be made into an invoice");
        }
        canWrite(me, SalesDocKind.INVOICE);
        List<SalesDoc> made = docRepository.fromQuotation(quotationId);
        if (!made.isEmpty()) {
            throw ApiException.conflict("Invoice " + label(made.getFirst()) + " was already made from this quotation");
        }
        if (q.getStatus() == SalesDocStatus.REJECTED) {
            throw ApiException.conflict("This quotation was rejected");
        }
        SalesDoc d = new SalesDoc();
        d.setKind(SalesDocKind.INVOICE);
        d.setBrand(q.getBrand());
        d.setStatus(SalesDocStatus.DRAFT);
        d.setCustomer(q.getCustomer());
        d.setDocDate(access.today());
        d.setQuotation(q);
        d.setCreatedBy(access.me(me));
        int n = 0;
        for (SalesDocLine ql : q.getLines()) {
            SalesDocLine l = new SalesDocLine();
            l.setDoc(d);
            l.setItem(ql.getItem());
            l.setItemCode(ql.getItemCode());
            l.setName(ql.getName());
            l.setTypeName(ql.getTypeName());
            l.setHsnCode(ql.getHsnCode());
            l.setSize(ql.getSize());
            l.setDescription(ql.getDescription());
            l.setUnit(ql.getUnit());
            l.setQuantity(ql.getQuantity());
            l.setRate(ql.getRate());
            l.setAmount(ql.getAmount());
            l.setUnitCost(ql.getItem() == null ? ql.getUnitCost() : ql.getItem().getSupplierPrice());
            l.setSortOrder(n++);
            d.getLines().add(l);
        }
        d.setDiscount(q.getDiscount());
        d.setMiscLabel(q.getMiscLabel());
        d.setMiscAmount(q.getMiscAmount());
        d.setGstRate(q.getGstRate());
        d.setInterState(q.isInterState());
        d.setRemarks(q.getRemarks());
        d.setTerms(q.getTerms());
        totals(d);
        q.setStatus(SalesDocStatus.ACCEPTED);
        return view(docRepository.saveAndFlush(d));
    }

    /** Gives the invoice its number and takes its items out of stock. */
    @Transactional
    public SalesView issue(AuthUser me, Long id) {
        SalesDoc d = full(me, id);
        canWrite(me, SalesDocKind.INVOICE);
        if (d.getKind() != SalesDocKind.INVOICE || d.getStatus() != SalesDocStatus.DRAFT) {
            throw ApiException.conflict("Only a draft invoice can be issued");
        }
        d.setNumber(numbers.next(d.getBrand(), TradeNumbers.SALE, d.getDocDate()));
        for (SalesDocLine l : d.getLines()) {
            if (l.getItem() != null) {
                ledger.move(l.getItem().getId(), StockMoveKind.SALE, l.getQuantity().negate(), d.getDocDate(), l.getUnitCost(),
                        null, d, "Sold: " + d.getNumber(), access.me(me), false);
            }
        }
        d.setStatus(SalesDocStatus.ISSUED);
        d.setIssuedAt(Instant.now());
        d.setIssuedBy(access.me(me));
        return view(docRepository.saveAndFlush(d));
    }

    /** An issued invoice: its items go back to stock. Payments must be refunded first. */
    @Transactional
    public SalesView cancel(AuthUser me, Long id, String reason) {
        SalesDoc d = full(me, id);
        canWrite(me, SalesDocKind.INVOICE);
        if (d.getKind() != SalesDocKind.INVOICE || d.getStatus() != SalesDocStatus.ISSUED) {
            throw ApiException.conflict(d.getStatus() == SalesDocStatus.DRAFT ? "A draft is deleted, not cancelled"
                    : "Only an issued invoice can be cancelled");
        }
        if (d.getPaid().signum() > 0) {
            throw ApiException.conflict("Refund the " + PayslipPdf.rupees(d.getPaid()) + " received first, then cancel");
        }
        for (SalesDocLine l : d.getLines()) {
            if (l.getItem() != null) {
                ledger.move(l.getItem().getId(), StockMoveKind.SALE_CANCEL, l.getQuantity(), access.today(), l.getUnitCost(),
                        null, d, "Cancelled: " + d.getNumber(), access.me(me), true);
            }
        }
        d.setStatus(SalesDocStatus.CANCELLED);
        d.setCancelledAt(Instant.now());
        d.setCancelReason(reason.strip());
        return view(docRepository.saveAndFlush(d));
    }

    /** Drafts and quotations not made into an invoice; never anything with payments. */
    @Transactional
    public void delete(AuthUser me, Long id) {
        SalesDoc d = full(me, id);
        canWrite(me, d.getKind());
        if (d.getKind() == SalesDocKind.INVOICE && d.getStatus() != SalesDocStatus.DRAFT) {
            throw ApiException.conflict("An issued invoice is cancelled, not deleted");
        }
        if (d.getKind() == SalesDocKind.QUOTATION && !docRepository.fromQuotation(id).isEmpty()) {
            throw ApiException.conflict("An invoice was made from this quotation");
        }
        if (!paymentRepository.forInvoice(id).isEmpty()) {
            throw ApiException.conflict("This invoice has payments; void or refund them, or keep it");
        }
        docRepository.delete(d);
    }

    // ---- payments ----

    @Transactional
    public SalesView addPayment(AuthUser me, Long id, PaymentBody body) {
        SalesDoc d = full(me, id);
        canWrite(me, SalesDocKind.INVOICE);
        if (d.getKind() != SalesDocKind.INVOICE || d.getStatus() == SalesDocStatus.CANCELLED) {
            throw ApiException.conflict("Payments go on an invoice that is not cancelled");
        }
        payments.record(me, d.getBrand(), TradeParty.CUSTOMER, d.getTotal(), d.getPaid(), body, p -> {
            p.setInvoice(d);
            p.setCustomer(d.getCustomer());
        });
        d.setPaid(TradePayments.paid(paymentRepository.forInvoice(id)));
        return view(docRepository.saveAndFlush(d));
    }

    @Transactional
    public SalesView voidPayment(AuthUser me, TradePayment p, String reason) {
        SalesDoc d = full(me, p.getInvoice().getId());
        canWrite(me, SalesDocKind.INVOICE);
        payments.voidIt(me, p, reason);
        BigDecimal paid = TradePayments.paid(paymentRepository.forInvoice(d.getId()));
        if (paid.signum() < 0) {
            throw ApiException.conflict("Void the refund first: more would be refunded than paid");
        }
        d.setPaid(paid);
        return view(docRepository.saveAndFlush(d));
    }

    // ---- helpers ----

    private void fill(AuthUser me, SalesDoc d, SalesBody body) {
        Customer c = parties.customerFull(me, body.getCustomerId());
        if (!c.getBrand().getId().equals(d.getBrand().getId())) {
            throw ApiException.badRequest("Choose a customer of " + d.getBrand().getName());
        }
        d.setCustomer(c);
        d.setDocDate(body.getDocDate());
        d.setValidUntil(d.getKind() == SalesDocKind.QUOTATION ? body.getValidUntil() : null);
        if (d.getValidUntil() != null && d.getValidUntil().isBefore(d.getDocDate())) {
            throw ApiException.badRequest("\"Valid until\" is before the quotation date");
        }
        d.getLines().clear();
        int n = 0;
        for (SalesLineBody lb : body.getLines()) {
            SalesDocLine l = new SalesDocLine();
            l.setDoc(d);
            if (lb.getItemId() != null) {
                ProductItem item = itemRepository.findFull(lb.getItemId())
                        .filter(i -> i.getBrand().getId().equals(d.getBrand().getId()))
                        .orElseThrow(() -> ApiException.badRequest("An item on line " + (body.getLines().indexOf(lb) + 1)
                                + " is not one of " + d.getBrand().getName() + "'s"));
                l.setItem(item);
                l.setItemCode(item.getCode());
                l.setTypeName(item.getType().getName());
                l.setUnitCost(item.getSupplierPrice());
                l.setHsnCode(StringUtils.hasText(lb.getHsnCode()) ? lb.getHsnCode().strip() : item.getHsnCode());
                l.setUnit(StringUtils.hasText(lb.getUnit()) ? lb.getUnit().strip() : item.getUnit());
            } else {
                l.setHsnCode(blank(lb.getHsnCode()));
                l.setUnit(StringUtils.hasText(lb.getUnit()) ? lb.getUnit().strip() : "nos");
            }
            l.setName(lb.getName().strip());
            l.setSize(blank(lb.getSize()));
            l.setDescription(blank(lb.getDescription()));
            l.setQuantity(lb.getQuantity().setScale(2, RoundingMode.HALF_UP));
            l.setRate(lb.getRate().setScale(2, RoundingMode.HALF_UP));
            l.setAmount(TradeMoney.amount(l.getQuantity(), l.getRate()));
            l.setSortOrder(n++);
            d.getLines().add(l);
        }
        d.setDiscount(TradeMoney.scale(body.getDiscount()));
        d.setMiscLabel(blank(body.getMiscLabel()));
        d.setMiscAmount(TradeMoney.scale(body.getMiscAmount()));
        d.setGstRate(body.getGstRate());
        d.setInterState(body.isInterState());
        d.setRemarks(blank(body.getRemarks()));
        d.setTerms(blank(body.getTerms()));
        totals(d);
    }

    private static void totals(SalesDoc d) {
        TradeMoney m = TradeMoney.of(d.getLines().stream().map(SalesDocLine::getAmount).toList(), d.getDiscount(),
                d.getMiscAmount(), d.getGstRate());
        d.setSubtotal(m.subtotal());
        d.setDiscount(m.discount());
        d.setMiscAmount(m.miscAmount());
        d.setTaxable(m.taxable());
        d.setGstRate(m.gstRate());
        d.setGstAmount(m.gstAmount());
        d.setRoundOff(m.roundOff());
        d.setTotal(m.total());
        d.setCostTotal(d.getLines().stream().map(l -> l.getUnitCost().multiply(l.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP));
    }

    SalesDoc full(AuthUser me, Long id) {
        SalesDoc d = docRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Not found: " + id));
        access.check(me, d.getBrand());
        return d;
    }

    private void canRead(AuthUser me, SalesDocKind kind) {
        access.require(me, "You cannot see " + (kind == SalesDocKind.INVOICE ? "invoices" : "quotations"),
                kind == SalesDocKind.INVOICE ? Menus.PROD_INVOICES : Menus.PROD_QUOTES, Menus.PROD_CUSTOMERS, Menus.PROD_REPORTS);
    }

    private void canWrite(AuthUser me, SalesDocKind kind) {
        access.require(me, "You cannot change " + (kind == SalesDocKind.INVOICE ? "invoices" : "quotations"),
                kind == SalesDocKind.INVOICE ? Menus.PROD_INVOICES : Menus.PROD_QUOTES);
    }

    private static boolean editable(SalesDoc d) {
        return d.getKind() == SalesDocKind.INVOICE ? d.getStatus() == SalesDocStatus.DRAFT
                : d.getStatus() == SalesDocStatus.DRAFT || d.getStatus() == SalesDocStatus.SENT;
    }

    private static String label(SalesDoc d) {
        return d.getNumber() == null ? "(draft)" : d.getNumber();
    }

    static String payStatus(SalesDoc d) {
        if (d.getKind() == SalesDocKind.QUOTATION || d.getStatus() == SalesDocStatus.CANCELLED) {
            return null;
        }
        return d.getStatus() == SalesDocStatus.DRAFT && d.getPaid().signum() == 0 ? null : TradeMoney.payStatus(d.getTotal(), d.getPaid());
    }

    static SalesRow row(SalesDoc d) {
        BigDecimal due = d.getKind() == SalesDocKind.INVOICE && d.getStatus() == SalesDocStatus.ISSUED
                ? d.getTotal().subtract(d.getPaid()).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        return new SalesRow(d.getId(), d.getKind(), d.getNumber(), d.getStatus(), payStatus(d), d.getBrand().getCode(),
                d.getCustomer().getId(), d.getCustomer().getName(), d.getCustomer().getPhone(), d.getDocDate(), d.getValidUntil(),
                d.getTotal(), d.getPaid(), due);
    }

    private SalesView view(SalesDoc d) {
        Customer c = d.getCustomer();
        List<SalesLineView> lines = new ArrayList<>();
        for (SalesDocLine l : d.getLines()) {
            boolean below = l.getItem() != null && l.getRate().compareTo(l.getItem().getMinSellPrice()) < 0;
            lines.add(new SalesLineView(l.getId(), l.getItem() == null ? null : l.getItem().getId(), l.getItemCode(), l.getName(),
                    l.getTypeName(), l.getHsnCode(), l.getSize(), l.getDescription(), l.getUnit(), l.getQuantity(), l.getRate(),
                    l.getAmount(), l.getUnitCost(), below));
        }
        SalesDoc invoice = d.getKind() == SalesDocKind.QUOTATION && d.getId() != null
                ? docRepository.fromQuotation(d.getId()).stream().findFirst().orElse(null) : null;
        List<PaymentView> pays = d.getId() == null || d.getKind() == SalesDocKind.QUOTATION ? List.of()
                : paymentRepository.forInvoice(d.getId()).stream().map(TradePayments::view).toList();
        BigDecimal margin = d.getSubtotal().subtract(d.getDiscount()).subtract(d.getCostTotal());
        return new SalesView(d.getId(), d.getKind(), d.getNumber(), d.getStatus(), payStatus(d), Company.of(d.getBrand()),
                new PartyRef(c.getId(), c.getCode(), c.getName(), c.getPhone(), c.getEmail(), c.getAddress(), c.getGstNumber()),
                d.getDocDate(), d.getValidUntil(), d.getQuotation() == null ? null : d.getQuotation().getId(),
                d.getQuotation() == null ? null : d.getQuotation().getNumber(), invoice == null ? null : invoice.getId(),
                invoice == null ? null : label(invoice), lines,
                TradeMoney.totals(d.getSubtotal(), d.getDiscount(), d.getMiscLabel(), d.getMiscAmount(), d.getTaxable(),
                        d.getGstRate(), d.isInterState(), d.getGstAmount(), d.getRoundOff(), d.getTotal(), d.getPaid()),
                d.getCostTotal(), margin, d.getRemarks(), d.getTerms(), pays,
                d.getCreatedBy() == null ? null : d.getCreatedBy().getName(), d.getCreatedAt(), d.getIssuedAt(),
                d.getIssuedBy() == null ? null : d.getIssuedBy().getName(), d.getCancelledAt(), d.getCancelReason(), editable(d));
    }
}
