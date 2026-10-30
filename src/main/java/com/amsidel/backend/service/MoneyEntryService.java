/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.AccountDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.*;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static com.amsidel.backend.service.CatalogService.blank;

/**
 * Other income and expenses: investment, loans, capital and other income; transport, fixing, maintenance, discounts
 * and other expenses, each optionally for an invoice, project, purchase bill or supplier. A discount with no money
 * moved on an invoice writes off what the customer did not pay: the invoice is settled at its billed amount (the PDF
 * stays as issued) and the difference is an expense of that customer, bill and project. Owners and admins delete.
 */
@Service
@RequiredArgsConstructor
public class MoneyEntryService {

    private static final int MAX_DAYS = 3 * 366;

    private final MoneyEntryRepository repository;
    private final SalesDocRepository docRepository;
    private final ProjectRepository projectRepository;
    private final PurchaseBillRepository billRepository;
    private final SupplierRepository supplierRepository;
    private final BankLineRepository lineRepository;
    private final TradeAccess access;
    private final TradeNumbers numbers;
    private final TradeHistoryLog history;
    private final MoneyTypeService types;

    void canUse(AuthUser me) {
        access.require(me, "You cannot see income and expenses", Menus.PROD_LEDGER, Menus.PROD_RECONCILE);
    }

    @Transactional(readOnly = true)
    public List<EntryView> list(AuthUser me, Long brandId, LocalDate from, LocalDate to) {
        canUse(me);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw ApiException.badRequest("Ask for at most three years at a time");
        }
        return repository.inRange(access.scope(me, brandId), from, to).stream().map(e -> view(me, e, false)).toList();
    }

    @Transactional(readOnly = true)
    public EntryView get(AuthUser me, Long id) {
        canUse(me);
        return view(me, full(me, id), true);
    }

    /** The invoices, projects, purchase bills and suppliers of a brand an entry can belong to. */
    @Transactional(readOnly = true)
    public EntryLinks links(AuthUser me, Long brandId) {
        canUse(me);
        Long scope = access.brandFor(me, brandId).getId();
        List<InvoiceOption> invoices = docRepository.openInvoices(scope).stream()
                .sorted(Comparator.comparing(SalesDoc::getDocDate).reversed().thenComparing(SalesDoc::getId, Comparator.reverseOrder()))
                .map(MoneyEntryService::option).toList();
        List<ProjectOption> projects = projectRepository.forBrand(scope).stream()
                .map(p -> new ProjectOption(p.getId(), p.getCode(), p.getName(), p.getCustomer().getId(), p.getCustomer().getName(), p.isSettled()))
                .toList();
        List<BillOption> bills = billRepository.openBills(scope).stream()
                .sorted(Comparator.comparing(PurchaseBill::getBillDate).reversed())
                .map(b -> new BillOption(b.getId(), b.getNumber(), b.getSupplierBillNo(), b.getSupplier().getId(), b.getSupplier().getName(), b.getTotal()))
                .toList();
        List<SupplierOption> suppliers = supplierRepository.forBrand(scope).stream().filter(Supplier::isActive)
                .map(s -> new SupplierOption(s.getId(), s.getCode(), s.getName())).toList();
        return new EntryLinks(invoices, projects, bills, suppliers);
    }

    static InvoiceOption option(SalesDoc d) {
        return new InvoiceOption(d.getId(), d.getNumber(), d.getDocDate(), d.getCustomer().getId(), d.getCustomer().getName(),
                d.getCustomer().getPhone(), d.getProject() == null ? null : d.getProject().getId(),
                d.getProject() == null ? null : d.getProject().getCode(), d.getTotal(), d.getPaid(), d.getWrittenOff(),
                d.getTotal().subtract(d.getCleared()).max(BigDecimal.ZERO));
    }

    @Transactional
    public EntryView create(AuthUser me, EntryBody body) {
        canUse(me);
        MoneyEntry e = new MoneyEntry();
        e.setBrand(access.brandFor(me, body.getBrandId()));
        e.setCreatedBy(access.me(me));
        fill(me, e, body);
        e.setNumber(numbers.next(e.getBrand(), e.getCategory().isIncome() ? "INC" : "EXP", e.getEntryDate()));
        repository.saveAndFlush(e);
        settle(e.getInvoice());
        log(me, e, "CREATED", describe(e));
        if (e.isWriteOff()) {
            logWriteOff(me, e, "Discount written off " + PayslipPdf.rupees(e.getAmount()) + " (" + e.getNumber() + ")");
        }
        return view(me, e, true);
    }

    @Transactional
    public EntryView update(AuthUser me, Long id, EntryBody body) {
        canUse(me);
        MoneyEntry e = full(me, id);
        lockedBySettledProject(me, e.getProject());
        MoneyType chosen = types.forEntry(body.getCategory(), e.getCategory());
        if (chosen.isIncome() != e.getCategory().isIncome()) {
            throw ApiException.badRequest(e.getCategory().isIncome()
                    ? "Income cannot become an expense: delete it and add the expense" : "An expense cannot become income: delete it and add the income");
        }
        if (body.getBrandId() != null && !body.getBrandId().equals(e.getBrand().getId())) {
            throw ApiException.badRequest("The brand cannot change");
        }
        SalesDoc before = e.getInvoice();
        boolean wasWriteOff = e.isWriteOff();
        TradeHistoryLog.Changes changes = new TradeHistoryLog.Changes()
                .add("For", e.getCategory().getLabel(), chosen.getLabel())
                .add("Date", e.getEntryDate(), body.getEntryDate())
                .add("Amount", e.getAmount(), body.getAmount().setScale(2, RoundingMode.HALF_UP))
                .add("Paid by", mode(e.getMode()), mode(body.getMode()))
                .add("Party", e.getPartyName(), blank(body.getPartyName()))
                .add("Transaction ID", e.getTransactionId(), blank(body.getTransactionId()))
                .add("Description", e.getDescription(), blank(body.getDescription()))
                .add("Remarks", e.getRemarks(), blank(body.getRemarks()));
        String linksBefore = links(e);
        fill(me, e, body);
        changes.add("Linked to", linksBefore, links(e));
        e.setUpdatedBy(access.me(me));
        repository.saveAndFlush(e);
        settle(before);
        if (e.getInvoice() != null && (before == null || !before.getId().equals(e.getInvoice().getId()))) {
            settle(e.getInvoice());
        }
        if (!changes.isEmpty()) {
            log(me, e, "UPDATED", changes.toString());
            if (wasWriteOff || e.isWriteOff()) {
                logWriteOff(me, e, "Discount written off changed (" + e.getNumber() + "): " + changes);
            }
        }
        return view(me, e, true);
    }

    @Transactional
    public void delete(AuthUser me, Long id) {
        canUse(me);
        access.requireAdmin(me, "Only owners and admins can delete income and expenses");
        MoneyEntry e = full(me, id);
        SalesDoc invoice = e.getInvoice();
        boolean writeOff = e.isWriteOff();
        log(me, e, "DELETED", describe(e));
        if (writeOff) {
            logWriteOff(me, e, "Discount written off removed: " + PayslipPdf.rupees(e.getAmount()) + " (" + e.getNumber() + ")");
        }
        repository.delete(e);
        repository.flush();
        settle(invoice);
    }

    /** What the customer did not pay, written off as a discount: the invoice is then settled. */
    @Transactional
    public EntryView writeOff(AuthUser me, Long invoiceId, WriteOffBody body) {
        canUse(me);
        SalesDoc d = docRepository.findFull(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));
        access.check(me, d.getBrand());
        BigDecimal due = d.getTotal().subtract(d.getCleared());
        if (due.signum() <= 0) {
            throw ApiException.conflict("Nothing is due on " + d.getNumber());
        }
        EntryBody e = new EntryBody();
        e.setBrandId(d.getBrand().getId());
        e.setCategory(MoneyType.DISCOUNT);
        e.setEntryDate(body.getEntryDate());
        e.setAmount(body.getAmount() == null ? due : body.getAmount());
        e.setDescription(blank(body.getDescription()) != null ? body.getDescription()
                : "Short payment on " + d.getNumber() + " written off as discount");
        e.setRemarks(body.getRemarks());
        e.setInvoiceId(d.getId());
        return create(me, e);
    }

    private void fill(AuthUser me, MoneyEntry e, EntryBody body) {
        Brand brand = e.getBrand();
        MoneyType category = types.forEntry(body.getCategory(), e.getCategory());
        if (body.getEntryDate().isAfter(access.today())) {
            throw ApiException.badRequest("The date cannot be in the future");
        }
        if (body.getMode() == null && !category.isDiscount()) {
            throw ApiException.badRequest("Choose how it was paid");
        }
        if (body.getMode() == null && body.getInvoiceId() == null) {
            throw ApiException.badRequest("A discount with no money moved goes on an invoice: choose the invoice");
        }
        BigDecimal amount = body.getAmount().setScale(2, RoundingMode.HALF_UP);

        SalesDoc invoice = null;
        Project project = null;
        if (body.getInvoiceId() != null) {
            invoice = docRepository.findFull(body.getInvoiceId()).orElseThrow(() -> ApiException.badRequest("Invoice not found"));
            if (!invoice.getBrand().getId().equals(brand.getId())) {
                throw ApiException.badRequest("That invoice is of another brand");
            }
            if (invoice.getKind() != SalesDocKind.INVOICE || invoice.getStatus() != SalesDocStatus.ISSUED) {
                throw ApiException.badRequest("Choose an issued invoice");
            }
            project = invoice.getProject();
        }
        if (body.getProjectId() != null) {
            Project chosen = projectRepository.findFull(body.getProjectId()).orElseThrow(() -> ApiException.badRequest("Project not found"));
            if (!chosen.getBrand().getId().equals(brand.getId())) {
                throw ApiException.badRequest("That project is of another brand");
            }
            if (project != null && !project.getId().equals(chosen.getId())) {
                throw ApiException.badRequest(invoice.getNumber() + " belongs to project " + project.getCode());
            }
            if (invoice != null && project == null && !chosen.getCustomer().getId().equals(invoice.getCustomer().getId())) {
                throw ApiException.badRequest("That project is of another customer than " + invoice.getNumber());
            }
            project = chosen;
        }
        lockedBySettledProject(me, project);
        if (category.isDiscount() && body.getMode() == null) {
            // written off: at most what is still due (leaving out this entry's own share before)
            BigDecimal mine = e.isWriteOff() && e.getInvoice().getId().equals(invoice.getId()) ? e.getAmount() : BigDecimal.ZERO;
            BigDecimal due = invoice.getTotal().subtract(invoice.getCleared()).add(mine);
            if (amount.compareTo(due) > 0) {
                throw ApiException.badRequest("Only " + PayslipPdf.rupees(due.max(BigDecimal.ZERO)) + " is still due on " + invoice.getNumber());
            }
        }

        PurchaseBill bill = null;
        Supplier supplier = null;
        if (body.getPurchaseBillId() != null) {
            bill = billRepository.findFull(body.getPurchaseBillId()).orElseThrow(() -> ApiException.badRequest("Purchase bill not found"));
            if (!bill.getBrand().getId().equals(brand.getId())) {
                throw ApiException.badRequest("That purchase bill is of another brand");
            }
            supplier = bill.getSupplier();
        }
        if (body.getSupplierId() != null) {
            Supplier chosen = supplierRepository.findFull(body.getSupplierId()).orElseThrow(() -> ApiException.badRequest("Supplier not found"));
            if (!chosen.getBrand().getId().equals(brand.getId())) {
                throw ApiException.badRequest("That supplier is of another brand");
            }
            if (supplier != null && !supplier.getId().equals(chosen.getId())) {
                throw ApiException.badRequest(bill.getNumber() + " is from " + supplier.getName());
            }
            supplier = chosen;
        }

        BankLine line = null;
        if (body.getBankLineId() != null) {
            line = lineRepository.findById(body.getBankLineId()).orElseThrow(() -> ApiException.badRequest("Bank line not found"));
            if (!line.getBrand().getId().equals(brand.getId())) {
                throw ApiException.badRequest("That bank line is of another brand");
            }
            boolean credit = line.getCredit().signum() > 0;
            if (credit != category.isIncome()) {
                throw ApiException.badRequest(credit ? "Money came in on that bank line: record it as income" : "Money went out on that bank line: record it as an expense");
            }
            if (body.getMode() == null || body.getMode() == TradePayMode.CASH) {
                throw ApiException.badRequest("A bank line is money through the bank: choose bank, UPI, cheque or card");
            }
            BigDecimal lineAmount = credit ? line.getCredit() : line.getDebit();
            Long lineId = line.getId();
            BigDecimal others = repository.forBankLines(List.of(lineId)).stream().filter(x -> !Objects.equals(x.getId(), e.getId()))
                    .map(MoneyEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (others.add(amount).compareTo(lineAmount) > 0) {
                throw ApiException.badRequest("That is more than the bank line's " + PayslipPdf.rupees(lineAmount)
                        + (others.signum() > 0 ? " (" + PayslipPdf.rupees(others) + " already recorded from it)" : ""));
            }
        }

        e.setCategory(category);
        e.setEntryDate(body.getEntryDate());
        e.setAmount(amount);
        e.setMode(body.getMode());
        e.setPartyName(blank(body.getPartyName()));
        e.setTransactionId(blank(body.getTransactionId()));
        e.setDescription(body.getDescription().strip());
        e.setRemarks(blank(body.getRemarks()));
        e.setInvoice(invoice);
        e.setProject(project);
        e.setCustomer(invoice != null ? invoice.getCustomer() : project != null ? project.getCustomer() : null);
        e.setPurchaseBill(bill);
        e.setSupplier(supplier);
        e.setBankLine(line);
    }

    /** A settled project is closed for everyone but owners and admins. */
    private void lockedBySettledProject(AuthUser me, Project p) {
        if (p != null && p.isSettled() && !access.admin(me)) {
            throw ApiException.forbidden("Project " + p.getCode() + " is settled: only an owner or admin can change its money");
        }
    }

    /** The invoice's written-off amount, from its discounts with no money moved. */
    private void settle(SalesDoc d) {
        if (d == null) {
            return;
        }
        BigDecimal off = repository.forInvoice(d.getId()).stream().filter(MoneyEntry::isWriteOff)
                .map(MoneyEntry::getAmount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (off.compareTo(d.getWrittenOff()) != 0) {
            d.setWrittenOff(off);
            docRepository.saveAndFlush(d);
        }
    }

    private MoneyEntry full(AuthUser me, Long id) {
        MoneyEntry e = repository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Entry not found: " + id));
        access.check(me, e.getBrand());
        return e;
    }

    private void log(AuthUser me, MoneyEntry e, String action, String details) {
        history.record(me, e.getBrand(), TradeSubject.MONEY_ENTRY, e.getId(),
                (e.getCategory().isIncome() ? "Income " : "Expense ") + e.getNumber() + " · " + e.getCategory().getLabel(),
                action, details, e.getProject() == null ? null : e.getProject().getId());
    }

    private void logWriteOff(AuthUser me, MoneyEntry e, String details) {
        SalesDoc d = e.getInvoice();
        history.record(me, d.getBrand(), TradeSubject.INVOICE, d.getId(), "Invoice " + d.getNumber(), "WRITE_OFF", details,
                d.getProject() == null ? null : d.getProject().getId());
    }

    static String mode(TradePayMode m) {
        return m == null ? "no money moved" : m.name();
    }

    /** "Transportation ₹1,200.00 by UPI on 2026-10-23 · invoice DWSLD-…". */
    static String describe(MoneyEntry e) {
        String links = links(e);
        return e.getCategory().getLabel() + " " + PayslipPdf.rupees(e.getAmount())
                + (e.getMode() == null ? " (no money moved)" : " by " + e.getMode().name()) + " on " + e.getEntryDate()
                + (e.getTransactionId() == null ? "" : " (txn " + e.getTransactionId() + ")") + ": " + e.getDescription()
                + (links.isEmpty() ? "" : " · " + links);
    }

    private static String links(MoneyEntry e) {
        StringBuilder s = new StringBuilder();
        if (e.getInvoice() != null) s.append("invoice ").append(e.getInvoice().getNumber()).append(' ');
        if (e.getProject() != null) s.append("project ").append(e.getProject().getCode()).append(' ');
        if (e.getCustomer() != null) s.append("customer ").append(e.getCustomer().getName()).append(' ');
        if (e.getPurchaseBill() != null) s.append("purchase ").append(e.getPurchaseBill().getNumber()).append(' ');
        if (e.getSupplier() != null) s.append("supplier ").append(e.getSupplier().getName()).append(' ');
        return s.toString().strip();
    }

    private EntryView view(AuthUser me, MoneyEntry e, boolean withHistory) {
        MoneyType c = e.getCategory();
        return new EntryView(e.getId(), e.getNumber(), e.getBrand().getCode(), e.getBrand().getId(), c.getCode(), c.getLabel(), c.isIncome(),
                c.isFunding(), e.getEntryDate(), e.getAmount(), e.getMode(), e.getPartyName(), e.getTransactionId(), e.getDescription(),
                e.getRemarks(), e.getInvoice() == null ? null : e.getInvoice().getId(), e.getInvoice() == null ? null : e.getInvoice().getNumber(),
                e.getProject() == null ? null : e.getProject().getId(), e.getProject() == null ? null : e.getProject().getCode(),
                e.getProject() == null ? null : e.getProject().getName(), e.getCustomer() == null ? null : e.getCustomer().getId(),
                e.getCustomer() == null ? null : e.getCustomer().getName(), e.getPurchaseBill() == null ? null : e.getPurchaseBill().getId(),
                e.getPurchaseBill() == null ? null : e.getPurchaseBill().getNumber(), e.getSupplier() == null ? null : e.getSupplier().getId(),
                e.getSupplier() == null ? null : e.getSupplier().getName(), e.getBankLine() == null ? null : e.getBankLine().getId(),
                e.isWriteOff(), e.getCreatedBy() == null ? null : e.getCreatedBy().getName(), e.getCreatedAt(),
                e.getUpdatedBy() == null ? null : e.getUpdatedBy().getName(), e.getUpdatedAt(), access.admin(me),
                withHistory && e.getId() != null ? history.of(List.of(TradeSubject.MONEY_ENTRY), e.getId()) : List.of());
    }
}
