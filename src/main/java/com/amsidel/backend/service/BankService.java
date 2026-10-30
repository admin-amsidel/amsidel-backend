/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.AccountDtos.BalanceBody;
import com.amsidel.backend.dto.AccountDtos.BalanceView;
import com.amsidel.backend.dto.AccountDtos.StatementView;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.BankBalanceRepository;
import com.amsidel.backend.repository.BankLineRepository;
import com.amsidel.backend.repository.BankStatementRepository;
import com.amsidel.backend.repository.MoneyEntryRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static com.amsidel.backend.service.CatalogService.blank;

/**
 * A brand's bank account in the portal: statements uploaded from Excel (they do not overlap) and balances typed in.
 * The balance on a day is the one typed in for that day, else the last statement line up to that day (or the
 * statement's opening balance the day before it starts).
 */
@Service
@RequiredArgsConstructor
public class BankService {

    private final BankStatementRepository statementRepository;
    private final BankLineRepository lineRepository;
    private final BankBalanceRepository balanceRepository;
    private final MoneyEntryRepository entryRepository;
    private final TradeAccess access;
    private final TradeHistoryLog history;

    private static final java.time.format.DateTimeFormatter MONTH = java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", java.util.Locale.ENGLISH);

    void canUse(AuthUser me) {
        access.require(me, "You cannot use the P&L reconciler", Menus.PROD_RECONCILE);
    }

    // ---- statements ----

    @Transactional
    public StatementView upload(AuthUser me, Long brandId, String fileName, byte[] bytes, String remarks) {
        canUse(me);
        Brand brand = access.brandFor(me, brandId);
        BankStatementReader.Statement read = BankStatementReader.read(fileName, bytes);
        List<BankStatementReader.Line> lines = read.lines();
        LocalDate from = lines.getFirst().date();
        LocalDate to = lines.getLast().date();
        if (to.isAfter(access.today())) {
            throw ApiException.badRequest("The statement has dates in the future (" + to + "): check the date column");
        }
        List<BankStatement> clash = statementRepository.overlapping(brand.getId(), from, to);
        if (!clash.isEmpty()) {
            BankStatement c = clash.getFirst();
            throw ApiException.conflict("A statement for " + c.getFromDate() + " to " + c.getToDate() + " (" + c.getFileName()
                    + ") is already uploaded for " + brand.getCode() + ": upload only the days after it, or delete it first");
        }
        BankStatement s = new BankStatement();
        s.setBrand(brand);
        s.setFileName(fileName == null || fileName.isBlank() ? "statement.xlsx" : cut(fileName.strip(), 200));
        s.setFromDate(from);
        s.setToDate(to);
        s.setOpeningBalance(read.opening());
        s.setClosingBalance(read.closing());
        s.setRemarks(blank(remarks) == null ? null : cut(remarks.strip(), 500));
        s.setUploadedBy(access.me(me));
        int n = 0;
        BigDecimal credit = BigDecimal.ZERO.setScale(2);
        BigDecimal debit = BigDecimal.ZERO.setScale(2);
        for (BankStatementReader.Line l : lines) {
            BankLine line = new BankLine();
            line.setStatement(s);
            line.setBrand(brand);
            line.setLineNo(++n);
            line.setTxnDate(l.date());
            line.setDescription(l.description());
            line.setReference(l.reference());
            line.setCredit(l.credit());
            line.setDebit(l.debit());
            line.setBalance(l.balance());
            s.getLines().add(line);
            credit = credit.add(l.credit());
            debit = debit.add(l.debit());
        }
        s.setTotalCredit(credit);
        s.setLineCount(n);
        s.setTotalDebit(debit);
        statementRepository.saveAndFlush(s);
        history.record(me, brand, TradeSubject.BANK_STATEMENT, s.getId(), "Bank statement " + from + " to " + to, "UPLOADED",
                s.getFileName() + ": " + n + " lines, credits " + PayslipPdf.rupees(credit) + ", debits " + PayslipPdf.rupees(debit)
                        + (s.getClosingBalance() == null ? "" : ", closing balance " + PayslipPdf.rupees(s.getClosingBalance())));
        return view(s, read.warnings());
    }

    @Transactional(readOnly = true)
    public List<StatementView> statements(AuthUser me, Long brandId) {
        canUse(me);
        return statementRepository.forBrand(access.scope(me, brandId)).stream().map(s -> view(s, List.of())).toList();
    }

    /** Owners and admins, or whoever uploaded it. Income and expenses recorded from its lines stay (unlinked). */
    @Transactional
    public void deleteStatement(AuthUser me, Long id) {
        canUse(me);
        BankStatement s = statementRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Statement not found: " + id));
        access.check(me, s.getBrand());
        if (!access.admin(me) && (s.getUploadedBy() == null || !s.getUploadedBy().getId().equals(me.id()))) {
            throw ApiException.forbidden("Only owners, admins or the person who uploaded it can delete a statement");
        }
        List<Long> lineIds = s.getLines().stream().map(BankLine::getId).toList();
        if (!lineIds.isEmpty()) {
            entryRepository.forBankLines(lineIds).forEach(e -> e.setBankLine(null));
            entryRepository.flush();
        }
        history.record(me, s.getBrand(), TradeSubject.BANK_STATEMENT, s.getId(), "Bank statement " + s.getFromDate() + " to " + s.getToDate(),
                "DELETED", s.getFileName() + ": " + s.getLineCount() + " lines");
        statementRepository.delete(s);
    }

    // ---- balances typed in ----

    @Transactional(readOnly = true)
    public List<BalanceView> balances(AuthUser me, Long brandId) {
        canUse(me);
        return balanceRepository.forBrand(access.scope(me, brandId)).stream().map(BankService::view).toList();
    }

    /**
     * One balance per brand and month, as at the month's last day (for the month still running: today). Typing a
     * month again replaces its balance and note; who first typed it stays, and who changed it last and when is kept.
     */
    @Transactional
    public BalanceView saveBalance(AuthUser me, BalanceBody body) {
        canUse(me);
        Brand brand = access.brandFor(me, body.getBrandId());
        YearMonth month = YearMonth.parse(body.getMonth());
        LocalDate today = access.today();
        if (month.isAfter(YearMonth.from(today))) {
            throw ApiException.badRequest("The month cannot be in the future");
        }
        LocalDate asOf = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
        BigDecimal amount = body.getBalance().setScale(2, RoundingMode.HALF_UP);
        List<BankBalance> existing = balanceRepository.inMonth(brand.getId(), month.atDay(1), month.atEndOfMonth());
        BankBalance b = existing.isEmpty() ? null : existing.getFirst();
        // more than one from before balances were monthly: the latest stays
        existing.stream().skip(1).forEach(balanceRepository::delete);
        String label = "Bank balance " + MONTH.format(month);
        if (b == null) {
            b = new BankBalance();
            b.setBrand(brand);
            b.setEnteredBy(access.me(me));
        } else {
            b.setUpdatedBy(access.me(me));
        }
        TradeHistoryLog.Changes changes = new TradeHistoryLog.Changes()
                .add("Balance", b.getBalance(), amount).add("As of", b.getAsOf(), asOf).add("Note", b.getNote(), blank(body.getNote()));
        boolean created = b.getId() == null;
        b.setAsOf(asOf);
        b.setBalance(amount);
        b.setNote(blank(body.getNote()));
        balanceRepository.saveAndFlush(b);
        if (created) {
            history.record(me, brand, TradeSubject.BANK_BALANCE, b.getId(), label, "CREATED",
                    PayslipPdf.rupees(amount) + " as of " + asOf + (b.getNote() == null ? "" : " · " + b.getNote()));
        } else if (!changes.isEmpty()) {
            history.record(me, brand, TradeSubject.BANK_BALANCE, b.getId(), label, "UPDATED", changes.toString());
        }
        return view(b);
    }

    @Transactional
    public void deleteBalance(AuthUser me, Long id) {
        canUse(me);
        BankBalance b = balanceRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Balance not found: " + id));
        access.check(me, b.getBrand());
        if (!access.admin(me) && (b.getEnteredBy() == null || !b.getEnteredBy().getId().equals(me.id()))) {
            throw ApiException.forbidden("Only owners, admins or the person who typed it in can delete a balance; type the month again to change it");
        }
        history.record(me, b.getBrand(), TradeSubject.BANK_BALANCE, b.getId(), "Bank balance " + MONTH.format(YearMonth.from(b.getAsOf())), "DELETED",
                PayslipPdf.rupees(b.getBalance()));
        balanceRepository.delete(b);
    }

    // ---- the balance on a day ----

    /** A balance known at the end of a day, and where it comes from. */
    public record Point(LocalDate date, BigDecimal balance, String source) {
    }

    /** The latest balance known up to the end of a day, or null. */
    public Point balanceAt(Long brandId, LocalDate day, List<BankStatement> statements) {
        Point best = null;
        BankBalance typed = balanceRepository.upTo(brandId, day, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        if (typed != null) {
            best = new Point(typed.getAsOf(), typed.getBalance(), "typed in");
        }
        BankLine line = lineRepository.balancesUpTo(brandId, day, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        if (line != null && (best == null || line.getTxnDate().isAfter(best.date()))) {
            best = new Point(line.getTxnDate(), line.getBalance(), "statement");
        }
        for (BankStatement s : statements) {
            LocalDate before = s.getFromDate().minusDays(1);
            if (s.getOpeningBalance() != null && !before.isAfter(day) && (best == null || before.isAfter(best.date()))) {
                best = new Point(before, s.getOpeningBalance(), "statement opening");
            }
        }
        return best;
    }

    /** Whether a statement covers the day, so every bank line of it is known. */
    public static boolean covered(LocalDate day, List<BankStatement> statements) {
        return statements.stream().anyMatch(s -> !day.isBefore(s.getFromDate()) && !day.isAfter(s.getToDate()));
    }

    /** The balance at the end of a day when it is certain: typed in for that day, or within a statement. */
    public BigDecimal knownAt(Long brandId, LocalDate day, List<BankStatement> statements) {
        Point p = balanceAt(brandId, day, statements);
        if (p == null) {
            return null;
        }
        return p.date().equals(day) || covered(day, statements) ? p.balance() : null;
    }

    /** Days whose closing balance is certain: typed in, the day before a statement starts, a statement's last day. */
    public List<Point> certain(Long brandId, List<BankStatement> statements) {
        List<Point> out = new java.util.ArrayList<>();
        balanceRepository.forBrand(brandId).forEach(b -> out.add(new Point(b.getAsOf(), b.getBalance(), "typed in")));
        for (BankStatement s : statements) {
            if (s.getOpeningBalance() != null) {
                out.add(new Point(s.getFromDate().minusDays(1), s.getOpeningBalance(), "statement opening"));
            }
            if (s.getClosingBalance() != null) {
                out.add(new Point(s.getToDate(), s.getClosingBalance(), "statement closing"));
            }
        }
        return out;
    }

    public List<BankStatement> statementsOf(Long brandId) {
        return statementRepository.forBrand(brandId);
    }

    // ---- the template ----

    public ExportService.Export template() {
        try (ExcelBook book = new ExcelBook()) {
            ExcelBook.Table t = book.sheet("Statement", "Bank statement",
                    "Fill one row per transaction (or upload the bank's own Excel). Credit = money in, Debit = money out.",
                    new String[]{"Date", "Description", "Reference", "Credit", "Debit", "Balance"}, new int[]{12, -50, 20, 14, 14, 16});
            t.row(LocalDate.of(2026, 10, 1), "NEFT-HDFC0001234-KUMAR RESIDENCE-ADVANCE", "UTR4471", new BigDecimal("50000"), null, new BigDecimal("150000"));
            t.row(LocalDate.of(2026, 10, 2), "UPI/TRANSPORT/MURUGAN LOGISTICS", "UPI-7781", null, new BigDecimal("2500"), new BigDecimal("147500"));
            t.done();
            return new ExportService.Export("BankStatementTemplate.xlsx", book.bytes());
        }
    }

    // ---- views ----

    static StatementView view(BankStatement s, List<String> warnings) {
        return new StatementView(s.getId(), s.getBrand().getCode(), s.getFileName(), s.getFromDate(), s.getToDate(), s.getOpeningBalance(),
                s.getClosingBalance(), s.getTotalCredit(), s.getTotalDebit(), s.getLineCount(), s.getRemarks(),
                s.getUploadedBy() == null ? null : s.getUploadedBy().getName(), s.getUploadedAt(), warnings);
    }

    static BalanceView view(BankBalance b) {
        return new BalanceView(b.getId(), b.getBrand().getCode(), YearMonth.from(b.getAsOf()).toString(), b.getAsOf(), b.getBalance(),
                b.getNote(), b.getEnteredBy() == null ? null : b.getEnteredBy().getName(), b.getCreatedAt(),
                b.getUpdatedBy() == null ? null : b.getUpdatedBy().getName(), b.getUpdatedAt());
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
