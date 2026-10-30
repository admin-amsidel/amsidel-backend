/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.AccountDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.*;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The P&L reconciler, for a brand (or all mine) and a date range, by day, week, month or financial year:
 * <ul>
 * <li>the money that moved: customer payments, other income and funding in; salaries, supplier payments and other
 * expenses out (bank and cash);</li>
 * <li>the profit and loss: sales and other income, less purchases, salaries, other expenses and discounts (before GST;
 * investment and loans left out);</li>
 * <li>the bank tally: the bank balance before + what the portal says went through the bank (not cash) = the balance
 * that should be after, against the bank's own balance;</li>
 * <li>each bank statement line matched to a payment, salary or entry in the portal, the portal's money not found in
 * the bank, and the invoices paid short (to collect, or to write off as a discount).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReconcileService {

    private static final int MAX_DAYS = 3 * 366;
    private static final int MAX_DAY_BUCKETS = 120;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final TradePaymentRepository paymentRepository;
    private final PayslipRepository payslipRepository;
    private final MoneyEntryRepository entryRepository;
    private final SalesDocRepository salesRepository;
    private final PurchaseBillRepository purchaseRepository;
    private final BankLineRepository lineRepository;
    private final MoneyTypeRepository typeRepository;
    private final BrandRepository brandRepository;
    private final BankService bank;
    private final TradeAccess access;

    /** Money in or out in the portal, for the bank tally and the matching. */
    record Move(String type, Long id, LocalDate date, boolean in, BigDecimal amount, String label, String number, Long docId,
                String reference, boolean cash, Long bankLineId) {
    }

    private record Data(List<TradePayment> payments, List<Payslip> salaries, List<MoneyEntry> entries, List<SalesDoc> sales,
                        List<PurchaseBill> purchases, List<Move> moves, List<BankLine> lines, List<BankStatement> statements,
                        List<BankService.Point> points) {
    }

    @Transactional(readOnly = true)
    public Report report(AuthUser me, Long brandId, LocalDate from, LocalDate to, String groupBy) {
        bank.canUse(me);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw ApiException.badRequest("Ask for at most three years at a time");
        }
        String by = groupBy == null ? "MONTH" : groupBy.toUpperCase(Locale.ROOT);
        if (by.equals("DAY") && ChronoUnit.DAYS.between(from, to) >= MAX_DAY_BUCKETS) {
            throw ApiException.badRequest("Day by day works for up to " + MAX_DAY_BUCKETS + " days: choose weeks or months");
        }
        Long scope = access.scope(me, brandId);
        Data data = load(scope, from, to);

        List<Bucket> buckets = new ArrayList<>();
        for (LocalDate[] b : buckets(by, from, to)) {
            buckets.add(bucket(key(by, b[0]), label(by, b[0], b[1]), b[0], b[1], scope, data));
        }
        Bucket total = bucket("TOTAL", "Total", from, to, scope, data);

        // the bank lines, matched to the portal's money
        Matching matching = scope == null ? new Matching(Map.of(), Set.of()) : match(data.lines(), data.moves());
        Set<Move> used = matching.matches().values().stream().flatMap(List::stream).collect(Collectors.toSet());
        List<BankLineView> lines = data.lines().stream()
                .map(l -> lineView(l, matching.matches().getOrDefault(l.getId(), List.of()), matching.partly().contains(l.getId()))).toList();
        List<Movement> notInBank = scope == null ? List.of() : data.moves().stream()
                .filter(m -> !m.cash() && !used.contains(m) && BankService.covered(m.date(), data.statements()))
                .map(m -> new Movement(m.type(), m.id(), m.date(), m.in(), m.amount(), m.label(), m.number(), m.docId(), m.reference()))
                .toList();

        List<ShortPaid> shortPaid = shortPaid(scope, to);
        List<String> notes = new ArrayList<>();
        if (scope == null) {
            notes.add("Choose a brand to tally its bank account; all brands together show the money and the P&L only.");
        } else if (data.statements().stream().noneMatch(s -> !s.getToDate().isBefore(from) && !s.getFromDate().isAfter(to))
                && total.tally().tallyFrom() == null) {
            notes.add("No bank statement or balance for these dates yet: upload the statement or type the balance in to tally.");
        }
        if (scope != null) {
            notes.add("Salaries count for the brand of each employee; people without a brand are only in All brands.");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("bankLines", lines.size());
        counts.put("matched", (int) lines.stream().filter(l -> l.status().equals("MATCHED")).count());
        counts.put("unmatched", (int) lines.stream().filter(l -> !l.status().equals("MATCHED")).count());
        counts.put("notInBank", notInBank.size());
        counts.put("shortPaid", shortPaid.size());
        return new Report(from, to, by, scope, scope == null ? null : code(scope),
                buckets, total, lines, notInBank, shortPaid,
                scope == null ? List.of() : data.statements().stream().map(s -> BankService.view(s, List.of())).toList(),
                scope == null ? List.of() : bank.balances(me, scope), notes, counts);
    }

    private String code(Long brandId) {
        return brandRepository.findById(brandId).map(Brand::getCode).orElse(null);
    }

    // ---- loading ----

    private Data load(Long scope, LocalDate from, LocalDate to) {
        List<TradePayment> payments = paymentRepository.inRange(scope, from, to).stream().filter(p -> !p.isVoided()).toList();
        List<Payslip> salaries = payslipRepository.paidBetween(from, to).stream()
                .filter(p -> scope == null || (p.getEmployee().getBrand() != null && p.getEmployee().getBrand().getId().equals(scope)))
                .toList();
        List<MoneyEntry> entries = entryRepository.inRange(scope, from, to);
        List<SalesDoc> sales = salesRepository.issued(scope, from, to);
        List<PurchaseBill> purchases = purchaseRepository.received(scope, from, to);
        List<Move> moves = new ArrayList<>();
        for (TradePayment p : payments) {
            boolean customer = p.getParty() == TradeParty.CUSTOMER;
            boolean refund = p.getKind() == TradePaymentKind.REFUND;
            boolean in = customer != refund;
            String who = customer ? (p.getCustomer() == null ? "" : p.getCustomer().getName()) : (p.getSupplier() == null ? "" : p.getSupplier().getName());
            String number = customer ? (p.getInvoice() == null ? null : p.getInvoice().getNumber()) : (p.getPurchaseBill() == null ? null : p.getPurchaseBill().getNumber());
            Long docId = customer ? (p.getInvoice() == null ? null : p.getInvoice().getId()) : (p.getPurchaseBill() == null ? null : p.getPurchaseBill().getId());
            String what = customer ? (refund ? "Refund to customer" : "Customer payment") : (refund ? "Refund from supplier" : "Supplier payment");
            moves.add(new Move(customer ? "CUSTOMER_PAYMENT" : "SUPPLIER_PAYMENT", p.getId(), p.getPaidOn(), in, p.getAmount(),
                    what + " · " + who, number, docId, p.getTransactionId(), p.getMode() == TradePayMode.CASH, null));
        }
        for (Payslip p : salaries) {
            moves.add(new Move("SALARY", p.getId(), p.getPaidOn(), false, net(p),
                    "Salary · " + p.getEmployee().getName() + " · " + MONTH.format(YearMonth.of(p.getPeriodYear(), p.getPeriodMonth())),
                    null, null, p.getPaymentRef(), p.getPaymentMode() == PaymentMode.CASH, null));
        }
        for (MoneyEntry e : entries) {
            if (e.getMode() == null) {
                continue;
            }
            moves.add(new Move("ENTRY", e.getId(), e.getEntryDate(), e.getCategory().isIncome(), e.getAmount(),
                    e.getCategory().getLabel() + " · " + e.getDescription(), e.getNumber(), null, e.getTransactionId(),
                    e.getMode() == TradePayMode.CASH, e.getBankLine() == null ? null : e.getBankLine().getId()));
        }
        List<BankLine> lines = scope == null ? List.of() : lineRepository.inRange(scope, from, to);
        List<BankStatement> statements = scope == null ? List.of() : bank.statementsOf(scope);
        List<BankService.Point> points = scope == null ? List.of() : bank.certain(scope, statements);
        return new Data(payments, salaries, entries, sales, purchases, moves, lines, statements, points);
    }

    /** Net pay: earnings - deductions + reimbursements. */
    static BigDecimal net(Payslip p) {
        BigDecimal n = BigDecimal.ZERO;
        for (PayslipLine l : p.getLines()) {
            n = switch (l.getKind()) {
                case EARNING, REIMBURSEMENT -> n.add(l.getAmount());
                case DEDUCTION -> n.subtract(l.getAmount());
            };
        }
        return n.setScale(2);
    }

    // ---- periods ----

    static List<LocalDate[]> buckets(String by, LocalDate from, LocalDate to) {
        List<LocalDate[]> out = new ArrayList<>();
        LocalDate start = from;
        while (!start.isAfter(to)) {
            LocalDate end = switch (by) {
                case "DAY" -> start;
                case "WEEK" -> start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                case "YEAR" -> LocalDate.of(start.getMonthValue() >= 4 ? start.getYear() + 1 : start.getYear(), 3, 31);
                default -> start.with(TemporalAdjusters.lastDayOfMonth());
            };
            if (end.isAfter(to)) {
                end = to;
            }
            out.add(new LocalDate[]{start, end});
            start = end.plusDays(1);
        }
        return out;
    }

    private static String key(String by, LocalDate start) {
        return switch (by) {
            case "YEAR" -> "FY" + (start.getMonthValue() >= 4 ? start.getYear() : start.getYear() - 1);
            case "MONTH" -> YearMonth.from(start).toString();
            default -> start.toString();
        };
    }

    private static String label(String by, LocalDate start, LocalDate end) {
        return switch (by) {
            case "DAY" -> DAY.format(start);
            case "WEEK" -> SHORT.format(start) + " – " + DAY.format(end);
            case "YEAR" -> {
                int fy = start.getMonthValue() >= 4 ? start.getYear() : start.getYear() - 1;
                yield "FY " + fy + "-" + String.format("%02d", (fy + 1) % 100);
            }
            default -> MONTH.format(start);
        };
    }

    // ---- one period ----

    private Bucket bucket(String key, String label, LocalDate from, LocalDate to, Long scope, Data data) {
        Predicate<LocalDate> in = d -> d != null && !d.isBefore(from) && !d.isAfter(to);
        List<TradePayment> pays = data.payments().stream().filter(p -> in.test(p.getPaidOn())).toList();
        List<Payslip> salaries = data.salaries().stream().filter(p -> in.test(p.getPaidOn())).toList();
        List<MoneyEntry> entries = data.entries().stream().filter(e -> in.test(e.getEntryDate())).toList();

        BigDecimal receipts = signed(pays, TradeParty.CUSTOMER);
        BigDecimal supplierPaid = signed(pays, TradeParty.SUPPLIER);
        BigDecimal salaryPaid = sum(salaries.stream().map(ReconcileService::net).toList());
        List<MoneyEntry> moved = entries.stream().filter(e -> e.getMode() != null).toList();
        BigDecimal otherIncome = sumEntries(moved, e -> e.getCategory().isIncome() && !e.getCategory().isFunding());
        BigDecimal funding = sumEntries(moved, e -> e.getCategory().isIncome() && e.getCategory().isFunding());
        BigDecimal otherExpenses = sumEntries(moved, e -> !e.getCategory().isIncome() && !e.getCategory().isFunding());
        BigDecimal fundingOut = sumEntries(moved, e -> !e.getCategory().isIncome() && e.getCategory().isFunding());
        BigDecimal moneyIn = receipts.add(otherIncome).add(funding);
        BigDecimal moneyOut = salaryPaid.add(supplierPaid).add(otherExpenses).add(fundingOut);

        List<Move> moves = data.moves().stream().filter(m -> in.test(m.date())).toList();
        BigDecimal cashIn = sum(moves.stream().filter(m -> m.cash() && m.in()).map(Move::amount).toList());
        BigDecimal cashOut = sum(moves.stream().filter(m -> m.cash() && !m.in()).map(Move::amount).toList());

        List<Part> inParts = new ArrayList<>();
        part(inParts, "CUSTOMER_PAYMENTS", "Customer payments (less refunds)", receipts);
        List<Part> outParts = new ArrayList<>();
        part(outParts, "SALARIES", "Salaries", salaryPaid);
        part(outParts, "SUPPLIER_PAYMENTS", "Supplier payments (less refunds)", supplierPaid);
        for (com.amsidel.backend.entity.MoneyType c : typeRepository.findAllByOrderByIncomeDescSortOrderAscLabelAsc()) {
            part(c.isIncome() ? inParts : outParts, c.getCode(), c.getLabel(), sumEntries(moved, e -> e.getCategory().getCode().equals(c.getCode())));
        }
        Flow flow = new Flow(receipts, otherIncome, funding, moneyIn, salaryPaid, supplierPaid, otherExpenses, fundingOut, moneyOut,
                moneyIn.subtract(moneyOut), cashIn, cashOut, inParts, outParts);

        // profit and loss: bills by their date, before GST
        List<SalesDoc> sales = data.sales().stream().filter(d -> in.test(d.getDocDate())).toList();
        List<PurchaseBill> bought = data.purchases().stream().filter(b -> in.test(b.getBillDate())).toList();
        BigDecimal salesNet = sum(sales.stream().map(d -> d.getTotal().subtract(d.getGstAmount())).toList());
        BigDecimal gstSales = sum(sales.stream().map(SalesDoc::getGstAmount).toList());
        BigDecimal purchasesNet = sum(bought.stream().map(b -> b.getTotal().subtract(b.getGstAmount())).toList());
        BigDecimal gstPurchases = sum(bought.stream().map(PurchaseBill::getGstAmount).toList());
        BigDecimal pnlOtherIncome = sumEntries(entries, e -> e.getCategory().isIncome() && !e.getCategory().isFunding());
        BigDecimal discounts = sumEntries(entries, e -> e.getCategory().isDiscount());
        BigDecimal pnlOther = sumEntries(entries, e -> !e.getCategory().isIncome() && !e.getCategory().isFunding()
                && !e.getCategory().isDiscount());
        BigDecimal revenue = salesNet.add(pnlOtherIncome);
        BigDecimal expenses = purchasesNet.add(salaryPaid).add(pnlOther).add(discounts);
        Pnl pnl = new Pnl(salesNet, pnlOtherIncome, revenue, purchasesNet, salaryPaid, pnlOther, discounts, expenses,
                revenue.subtract(expenses), gstSales, gstPurchases);

        // the bank: not cash
        Tally tally;
        if (scope == null) {
            tally = new Tally(null, null, null, null, null, null, null, null, false, null, null);
        } else {
            tally = tally(scope, from, to, data);
        }
        return new Bucket(key, label, from, to, flow, pnl, tally);
    }

    /**
     * The bank over the part of a period whose balances are known: from the balance the day before it (else the first
     * certain balance in it) to the balance on its last day (else the last certain balance in it).
     */
    private Tally tally(Long scope, LocalDate from, LocalDate to, Data data) {
        List<BankStatement> st = data.statements();
        LocalDate start = null;
        BigDecimal opening = bank.knownAt(scope, from.minusDays(1), st);
        if (opening != null) {
            start = from;
        } else {
            BankService.Point first = data.points().stream()
                    .filter(p -> !p.date().isBefore(from.minusDays(1)) && p.date().isBefore(to))
                    .min(Comparator.comparing(BankService.Point::date)).orElse(null);
            if (first != null) {
                start = first.date().plusDays(1);
                opening = bank.knownAt(scope, first.date(), st);
            }
        }
        LocalDate end = null;
        BigDecimal actual = null;
        if (start != null && opening != null) {
            actual = bank.knownAt(scope, to, st);
            if (actual != null) {
                end = to;
            } else {
                LocalDate s = start;
                BankService.Point last = data.points().stream().filter(p -> !p.date().isBefore(s) && !p.date().isAfter(to))
                        .max(Comparator.comparing(BankService.Point::date)).orElse(null);
                if (last != null) {
                    end = last.date();
                    actual = bank.knownAt(scope, end, st);
                }
            }
        }
        // what the portal has through the bank: over the known part, else the whole period
        boolean known = end != null && actual != null;
        LocalDate a = known ? start : from;
        LocalDate b = known ? end : to;
        Predicate<LocalDate> in = d -> d != null && !d.isBefore(a) && !d.isAfter(b);
        List<Move> moves = data.moves().stream().filter(m -> !m.cash() && in.test(m.date())).toList();
        BigDecimal portalIn = sum(moves.stream().filter(Move::in).map(Move::amount).toList());
        BigDecimal portalOut = sum(moves.stream().filter(m -> !m.in()).map(Move::amount).toList());
        BigDecimal expected = known ? opening.add(portalIn).subtract(portalOut) : null;
        List<BankLine> lines = data.lines().stream().filter(l -> in.test(l.getTxnDate())).toList();
        boolean covered = BankService.covered(from, st) && BankService.covered(to, st);
        boolean anyBank = !lines.isEmpty() || known;
        return new Tally(known ? opening : null, portalIn, portalOut, expected, known ? actual : null,
                known ? actual.subtract(expected) : null,
                anyBank ? sum(lines.stream().map(BankLine::getCredit).toList()) : null,
                anyBank ? sum(lines.stream().map(BankLine::getDebit).toList()) : null, covered,
                known ? a : null, known ? b : null);
    }

    private static void part(List<Part> parts, String key, String label, BigDecimal amount) {
        if (amount.signum() != 0) {
            parts.add(new Part(key, label, amount));
        }
    }

    private static BigDecimal signed(List<TradePayment> pays, TradeParty party) {
        return sum(pays.stream().filter(p -> p.getParty() == party)
                .map(p -> p.getKind() == TradePaymentKind.REFUND ? p.getAmount().negate() : p.getAmount()).toList());
    }

    private static BigDecimal sumEntries(List<MoneyEntry> entries, Predicate<MoneyEntry> which) {
        return sum(entries.stream().filter(which).map(MoneyEntry::getAmount).toList());
    }

    static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(ZERO, BigDecimal::add).setScale(2);
    }

    // ---- matching bank lines ----

    /**
     * Each bank line to the portal's money (not cash) of the same direction and amount: first entries recorded from
     * the line; then a transaction ID found in the line within 10 days; then the nearest date within 4 days; then a
     * day's salaries paid in one go.
     */
    /** Bank line id → the portal's money it is; partly: lines whose recorded entries add up to less than the line. */
    record Matching(Map<Long, List<Move>> matches, Set<Long> partly) {
    }

    static Matching match(List<BankLine> lines, List<Move> all) {
        List<Move> free = new ArrayList<>(all.stream().filter(m -> !m.cash()).toList());
        Map<Long, List<Move>> out = new HashMap<>();
        Set<Long> done = new HashSet<>();
        for (BankLine l : lines) {
            List<Move> linked = free.stream().filter(m -> l.getId().equals(m.bankLineId())).toList();
            if (!linked.isEmpty()) {
                out.put(l.getId(), new ArrayList<>(linked));
                free.removeAll(linked);
                if (sum(linked.stream().map(Move::amount).toList()).compareTo(amount(l)) == 0) {
                    done.add(l.getId());
                }
            }
        }
        // a free move that names a bank line not in view stays out of the other passes
        free.removeIf(m -> m.bankLineId() != null);
        for (BankLine l : lines) {
            if (out.containsKey(l.getId())) {
                continue;
            }
            String text = normal((l.getDescription() == null ? "" : l.getDescription()) + " " + (l.getReference() == null ? "" : l.getReference()));
            free.stream().filter(m -> same(l, m) && m.reference() != null && normal(m.reference()).length() >= 4
                            && text.contains(normal(m.reference())) && days(l, m) <= 10)
                    .min(Comparator.comparingLong(m -> days(l, m))).ifPresent(m -> take(out, done, free, l, List.of(m)));
        }
        for (BankLine l : lines) {
            if (out.containsKey(l.getId())) {
                continue;
            }
            free.stream().filter(m -> same(l, m) && days(l, m) <= 4)
                    .min(Comparator.comparingLong(m -> days(l, m))).ifPresent(m -> take(out, done, free, l, List.of(m)));
        }
        for (BankLine l : lines) {
            if (out.containsKey(l.getId()) || l.getDebit().signum() == 0) {
                continue;
            }
            Map<LocalDate, List<Move>> byDay = free.stream().filter(m -> m.type().equals("SALARY"))
                    .collect(Collectors.groupingBy(Move::date));
            byDay.entrySet().stream()
                    .filter(e -> e.getValue().size() > 1 && Math.abs(ChronoUnit.DAYS.between(e.getKey(), l.getTxnDate())) <= 4
                            && sum(e.getValue().stream().map(Move::amount).toList()).compareTo(l.getDebit()) == 0)
                    .findFirst().ifPresent(e -> take(out, done, free, l, e.getValue()));
        }
        Set<Long> partly = out.keySet().stream().filter(id -> !done.contains(id)).collect(Collectors.toSet());
        return new Matching(out, partly);
    }

    private static void take(Map<Long, List<Move>> out, Set<Long> done, List<Move> free, BankLine l, List<Move> ms) {
        out.put(l.getId(), new ArrayList<>(ms));
        done.add(l.getId());
        free.removeAll(ms);
    }

    private static BigDecimal amount(BankLine l) {
        return l.getCredit().signum() > 0 ? l.getCredit() : l.getDebit();
    }

    private static boolean same(BankLine l, Move m) {
        return (l.getCredit().signum() > 0) == m.in() && amount(l).compareTo(m.amount()) == 0;
    }

    private static long days(BankLine l, Move m) {
        return Math.abs(ChronoUnit.DAYS.between(l.getTxnDate(), m.date()));
    }

    private static String normal(String s) {
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static BankLineView lineView(BankLine l, List<Move> ms, boolean partly) {
        BigDecimal recorded = sum(ms.stream().map(Move::amount).toList());
        String status = ms.isEmpty() ? "UNMATCHED" : partly ? "PARTLY" : "MATCHED";
        return new BankLineView(l.getId(), l.getTxnDate(), l.getDescription(), l.getReference(), l.getCredit(), l.getDebit(),
                l.getBalance(), status, ms.stream().map(m -> new Match(m.type(), m.id(), m.number(), m.label(), m.docId())).toList(),
                recorded);
    }

    // ---- invoices paid short ----

    private List<ShortPaid> shortPaid(Long scope, LocalDate to) {
        LocalDate today = access.today();
        return salesRepository.openInvoices(scope).stream()
                .filter(d -> d.getStatus() == SalesDocStatus.ISSUED && !d.getDocDate().isAfter(to)
                        && d.getTotal().compareTo(d.getCleared()) > 0)
                .map(d -> {
                    LocalDate last = paymentRepository.forInvoice(d.getId()).stream().filter(p -> !p.isVoided())
                            .map(TradePayment::getPaidOn).max(Comparator.naturalOrder()).orElse(null);
                    Project p = d.getProject();
                    return new ShortPaid(d.getId(), d.getNumber(), d.getDocDate(), d.getBrand().getCode(), d.getCustomer().getId(),
                            d.getCustomer().getName(), d.getCustomer().getPhone(), p == null ? null : p.getId(), p == null ? null : p.getCode(),
                            p == null ? null : p.getName(), d.getTotal(), d.getPaid(), d.getWrittenOff(), d.getTotal().subtract(d.getCleared()),
                            last, ChronoUnit.DAYS.between(d.getDocDate(), today));
                })
                .sorted(Comparator.comparing(ShortPaid::date)).toList();
    }

    // ---- Excel ----

    @Transactional(readOnly = true)
    public ExportService.Export excel(AuthUser me, Long brandId, LocalDate from, LocalDate to, String groupBy) {
        Report r = report(me, brandId, from, to, groupBy);
        String scope = r.brandCode() == null ? "All brands" : r.brandCode();
        String period = DAY.format(from) + " to " + DAY.format(to);
        try (ExcelBook book = new ExcelBook()) {
            ExcelBook.Table pnl = book.sheet("Profit & loss", "Profit & loss · " + scope, period
                            + ". Amounts before GST; investment and loans left out.",
                    new String[]{"Period", "Sales", "Other income", "Revenue", "Purchases", "Salaries", "Other expenses", "Discounts",
                            "Expenses", "Profit / loss", "GST on sales", "GST on purchases"},
                    new int[]{24, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14});
            for (Bucket b : withTotal(r)) {
                Pnl p = b.pnl();
                Object[] row = {b.label(), p.sales(), p.otherIncome(), p.revenue(), p.purchases(), p.salaries(), p.otherExpenses(),
                        p.discounts(), p.expenses(), p.profit(), p.gstOnSales(), p.gstOnPurchases()};
                if (b == r.total()) pnl.totals(row); else pnl.row(row);
            }
            ExcelBook.Table flow = book.sheet("Money in & out", "Money in and out · " + scope, period + ". Bank, UPI, cheque, card and cash.",
                    new String[]{"Period", "Customer payments", "Other income", "Investment & loans in", "Money in", "Salaries",
                            "Supplier payments", "Other expenses", "Loan repayments", "Money out", "Net", "Cash in", "Cash out"},
                    new int[]{24, 16, 14, 16, 14, 14, 16, 14, 14, 14, 14, 12, 12});
            for (Bucket b : withTotal(r)) {
                Flow f = b.flow();
                Object[] row = {b.label(), f.customerReceipts(), f.otherIncome(), f.funding(), f.moneyIn(), f.salaries(), f.supplierPayments(),
                        f.otherExpenses(), f.fundingOut(), f.moneyOut(), f.net(), f.cashIn(), f.cashOut()};
                if (b == r.total()) flow.totals(row); else flow.row(row);
            }
            if (r.brandId() != null) {
                ExcelBook.Table tally = book.sheet("Bank tally", "Bank tally · " + scope, period
                                + ". Balance before + portal in - portal out (not cash) = should be; against the bank's balance.",
                        new String[]{"Period", "Balance before", "Portal: in", "Portal: out", "Should be", "Bank balance", "Difference",
                                "Statement credits", "Statement debits"},
                        new int[]{24, 16, 14, 14, 16, 16, 14, 16, 16});
                for (Bucket b : withTotal(r)) {
                    Tally t = b.tally();
                    Object[] row = {b.label(), t.opening(), t.portalIn(), t.portalOut(), t.expected(), t.actual(), t.difference(), t.bankIn(), t.bankOut()};
                    if (b == r.total()) tally.totals(row); else tally.row(row);
                }
                ExcelBook.Table lines = book.sheet("Bank lines", "Bank statement lines · " + scope, period,
                        new String[]{"Date", "Description", "Reference", "Credit", "Debit", "Balance", "Status", "Matched to"},
                        new int[]{12, -50, 18, 14, 14, 16, 12, -50});
                for (BankLineView l : r.bankLines()) {
                    lines.row(l.date(), l.description(), l.reference(), nz(l.credit()), nz(l.debit()), l.balance(), l.status(),
                            l.matches().stream().map(m -> (m.number() == null ? "" : m.number() + " ") + m.label()).collect(Collectors.joining("; ")));
                }
                lines.done();
                ExcelBook.Table missing = book.sheet("Not in bank", "In the portal, not in the bank statement · " + scope, period,
                        new String[]{"Date", "What", "Bill / entry", "In", "Out", "Transaction ID"}, new int[]{12, -50, 22, 14, 14, 20});
                for (Movement m : r.notInBank()) {
                    missing.row(m.date(), m.label(), m.number(), m.in() ? m.amount() : null, m.in() ? null : m.amount(), m.reference());
                }
                missing.done();
            }
            ExcelBook.Table shortPaid = book.sheet("Invoices paid short", "Invoices not fully paid · " + scope, "Issued up to " + DAY.format(to),
                    new String[]{"Invoice", "Date", "Customer", "Phone", "Project", "Billed", "Paid", "Written off", "Difference", "Last paid", "Days open"},
                    new int[]{22, 12, 26, 18, 22, 14, 14, 14, 14, 12, 10});
            for (ShortPaid s : r.shortPaid()) {
                shortPaid.row(s.number(), s.date(), s.customerName(), s.customerPhone(), s.projectCode(), s.total(), s.paid(), s.writtenOff(),
                        s.due(), s.lastPaidOn(), s.daysOpen());
            }
            shortPaid.done();
            return new ExportService.Export("PnL-" + (r.brandCode() == null ? "All" : r.brandCode()) + "-" + from + "-to-" + to + ".xlsx", book.bytes());
        }
    }

    private static List<Bucket> withTotal(Report r) {
        List<Bucket> all = new ArrayList<>(r.buckets());
        all.add(r.total());
        return all;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null || v.signum() == 0 ? null : v;
    }
}
