/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.PayrollViews.Person;
import com.amsidel.backend.dto.PayrollViews.SalaryView;
import com.amsidel.backend.dto.PayslipDtos.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.PayslipRepository;
import com.amsidel.backend.repository.SalaryStructureRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monthly payslips made from each employee's salary for the year.
 * Making one: the month has the salary's working days (default 26; payroll may set the month's own). Per-day pay =
 * monthly earnings / working days; earnings are paid for the paid days = working days employed - LOP days (a salary
 * starting mid-month counts its working days, i.e. not the weekly offs, from the start date). Deductions and
 * reimbursements are the salary's amounts. Income tax (New or Old regime) is worked out again for the month whenever a
 * draft is made, saved or made again: the year's income expected, less the tax of the earlier months, over the months
 * left (see workOutTax); a Manual tax salary keeps the amount typed.
 * Payroll can change any amount while it is a DRAFT, then publishes it and records the salary as credited (when,
 * bank / cash / UPI, reference). The employee sees a payslip only when it is published and credited.
 * Who sees whose: as for salaries (PayrollAccess).
 */
@Service
@RequiredArgsConstructor
public class PayslipService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final PayslipRepository payslipRepository;
    private final SalaryStructureRepository salaryRepository;
    private final EmployeeRepository employeeRepository;
    private final PayrollAccess access;
    private final PayrollService payrollService;

    @Value("${app.leave.zone:Asia/Kolkata}")
    private String zone;

    /** Days the office is closed every week (as for leave), left out when counting a part month's working days. */
    @Value("${app.leave.weekly-offs:SUNDAY}")
    private Set<java.time.DayOfWeek> weeklyOffs;

    @Value("${app.payslip.company-name:Amsidel Enterprises Private Limited}")
    private String companyName;

    @Value("${app.payslip.company-address:}")
    private String companyAddress;

    // ---- payroll: the month ----

    @Transactional(readOnly = true)
    public MonthView month(AuthUser me, int year, int month) {
        YearMonth ym = yearMonth(year, month);
        int fy = fyOf(ym);
        Map<Long, SalaryStructure> salaries = salaryRepository.ofYear(fy).stream()
                .collect(Collectors.toMap(s -> s.getEmployee().getId(), Function.identity()));
        Map<Long, Payslip> payslips = payslipRepository.ofMonth(year, month).stream()
                .collect(Collectors.toMap(p -> p.getEmployee().getId(), Function.identity()));
        List<Row> rows = new ArrayList<>();
        List<Money> made = new ArrayList<>();
        int eligible = 0;
        int drafts = 0;
        int published = 0;
        int credited = 0;
        for (Employee e : access.people(me)) {
            SalaryStructure s = salaries.get(e.getId());
            Payslip p = payslips.get(e.getId());
            boolean salarySet = s != null && !s.getEffectiveFrom().isAfter(ym.atEndOfMonth());
            if (p == null && (!e.isActive() || !salarySet)) {
                if (e.isActive()) {
                    rows.add(new Row(Person.of(e), false, null, null, 0, 0, 0, money(List.of()), access.editable(me, e), null, null));
                }
                continue;
            }
            eligible++;
            if (p == null) {
                rows.add(new Row(Person.of(e), true, null, null, s.getWorkingDays(), 0, 0, money(List.of()), access.editable(me, e), null, null));
                continue;
            }
            Money m = money(p.getLines());
            made.add(m);
            if (p.getStatus() == PayslipStatus.DRAFT) {
                drafts++;
            } else {
                published++;
                if (p.getPaidOn() != null) {
                    credited++;
                }
            }
            rows.add(new Row(Person.of(e), salarySet, p.getId(), p.getStatus(), p.getWorkingDays(), p.getPaidDays(), p.getLopDays(), m,
                    access.editable(me, e), p.getPaidOn(), p.getPaymentMode()));
        }
        return new MonthView(year, month, label(ym), fy, rows, eligible, drafts, published, credited, sum(made));
    }

    /** Draft payslips for everyone (or these people) with a salary that month and no payslip yet. */
    @Transactional
    public GenerateResult generate(AuthUser me, Generate body) {
        YearMonth ym = yearMonth(body.getYear(), body.getMonth());
        int fy = fyOf(ym);
        Set<Long> only = body.getEmployeeIds() == null || body.getEmployeeIds().isEmpty() ? null : Set.copyOf(body.getEmployeeIds());
        Map<Long, SalaryStructure> salaries = salaryRepository.ofYear(fy).stream()
                .collect(Collectors.toMap(s -> s.getEmployee().getId(), Function.identity()));
        Employee by = employeeRepository.getReferenceById(me.id());
        int created = 0;
        int skipped = 0;
        List<String> notes = new ArrayList<>();
        for (Employee e : access.people(me)) {
            if (only != null && !only.contains(e.getId())) {
                continue;
            }
            SalaryStructure s = salaries.get(e.getId());
            if (!e.isActive() || s == null || s.getEffectiveFrom().isAfter(ym.atEndOfMonth())) {
                if (only != null) {
                    skipped++;
                    notes.add(e.getName() + ": no salary for " + label(ym));
                }
                continue;
            }
            if (!access.editable(me, e)) {
                skipped++;
                notes.add(e.getName() + ": your own payslip is made by someone else");
                continue;
            }
            if (payslipRepository.existsByEmployee_IdAndPeriodYearAndPeriodMonth(e.getId(), ym.getYear(), ym.getMonthValue())) {
                skipped++;
                continue;
            }
            Payslip p = new Payslip();
            p.setEmployee(e);
            p.setFyStartYear(fy);
            p.setPeriodYear(ym.getYear());
            p.setPeriodMonth(ym.getMonthValue());
            p.setPayDate(ym.atEndOfMonth());
            p.setDesignationName(e.getDesignation() == null ? null : e.getDesignation().getName());
            p.setUpdatedBy(by);
            fill(p, s, ym, body.getWorkingDays() != null ? body.getWorkingDays() : s.getWorkingDays(), 0);
            applyTax(p, s);
            payslipRepository.save(p);
            created++;
        }
        return new GenerateResult(created, skipped, notes);
    }

    @Transactional(readOnly = true)
    public PayslipView payslip(AuthUser me, Long id) {
        Payslip p = full(id);
        access.visible(me, p.getEmployee().getId());
        return view(p, access.editable(me, p.getEmployee()), true);
    }

    @Transactional
    public PayslipView save(AuthUser me, Long id, Save body) {
        Payslip p = full(id);
        Employee e = access.visible(me, p.getEmployee().getId());
        access.requireEditable(me, e);
        if (p.getStatus() == PayslipStatus.PUBLISHED) {
            throw ApiException.conflict("This payslip is published; move it back to draft to change it");
        }
        YearMonth ym = YearMonth.of(p.getPeriodYear(), p.getPeriodMonth());
        if (body.getPayDate().isBefore(ym.atDay(1)) || body.getPayDate().isAfter(ym.atEndOfMonth().plusDays(15))) {
            throw ApiException.badRequest("The pay date must be in " + label(ym) + " or within 15 days after it");
        }
        if (body.getWorkingDays() != null && body.getWorkingDays() != p.getWorkingDays()) {
            int working = body.getWorkingDays();
            LocalDate start = salaryRepository.find(e.getId(), p.getFyStartYear()).map(SalaryStructure::getEffectiveFrom).orElse(ym.atDay(1));
            p.setWorkingDays(working);
            p.setEmployedDays(employedDays(start, ym, working));
        }
        if (body.getLopDays() > p.getEmployedDays()) {
            throw ApiException.badRequest("Unpaid (LOP) days cannot be more than the " + p.getEmployedDays()
                    + " working days employed this month");
        }
        Map<Long, PayslipLine> lines = p.getLines().stream().collect(Collectors.toMap(PayslipLine::getId, Function.identity()));
        for (LineAmount la : body.getLines()) {
            PayslipLine l = lines.get(la.getId());
            if (l == null) {
                throw ApiException.badRequest("Line " + la.getId() + " is not on this payslip");
            }
            l.setAmount(la.getAmount().setScale(2, RoundingMode.HALF_UP));
        }
        p.setPayDate(body.getPayDate());
        p.setLopDays(body.getLopDays());
        p.setPaidDays(p.getEmployedDays() - body.getLopDays());
        // the income tax follows the pay (New or Old regime), whatever was typed in its line
        applyTax(p, salaryRepository.find(e.getId(), p.getFyStartYear()).orElse(null));
        if (money(p.getLines()).net().signum() < 0) {
            throw ApiException.badRequest("Deductions are more than the pay; check the amounts");
        }
        p.setUpdatedBy(employeeRepository.getReferenceById(me.id()));
        if (body.isPublish()) {
            publishOne(me, p);
        }
        return view(payslipRepository.saveAndFlush(p), true, true);
    }

    /** Makes a draft again from the current salary (after the salary was changed), keeping its unpaid days. */
    @Transactional
    public PayslipView recalculate(AuthUser me, Long id) {
        Payslip p = full(id);
        Employee e = access.visible(me, p.getEmployee().getId());
        access.requireEditable(me, e);
        if (p.getStatus() == PayslipStatus.PUBLISHED) {
            throw ApiException.conflict("This payslip is published; move it back to draft to change it");
        }
        SalaryStructure s = salaryRepository.find(e.getId(), p.getFyStartYear())
                .orElseThrow(() -> ApiException.conflict("There is no salary for " + e.getName() + " that year any more"));
        YearMonth ym = YearMonth.of(p.getPeriodYear(), p.getPeriodMonth());
        if (s.getEffectiveFrom().isAfter(ym.atEndOfMonth())) {
            throw ApiException.conflict("The salary now starts after " + label(ym));
        }
        p.getLines().clear();
        payslipRepository.flush();
        fill(p, s, ym, p.getWorkingDays(), p.getLopDays());
        applyTax(p, s);
        p.setUpdatedBy(employeeRepository.getReferenceById(me.id()));
        return view(payslipRepository.saveAndFlush(p), true, true);
    }

    /** Publishes these drafts (the person's own and other people's out of reach are left out). */
    @Transactional
    public int publish(AuthUser me, List<Long> ids) {
        int done = 0;
        for (Long id : ids) {
            Payslip p = full(id);
            Employee e = access.visible(me, p.getEmployee().getId());
            if (p.getStatus() == PayslipStatus.DRAFT && access.editable(me, e)) {
                publishOne(me, p);
                done++;
            }
        }
        return done;
    }

    /** The salary was credited: the employee now sees the payslip. Only a published payslip can be credited. */
    @Transactional
    public PayslipView markPaid(AuthUser me, Long id, Payment body) {
        Payslip p = full(id);
        Employee e = access.visible(me, p.getEmployee().getId());
        access.requireEditable(me, e);
        credit(me, p, body.getPaidOn(), body.getMode(), body.getReference(), body.getRemarks());
        return view(payslipRepository.saveAndFlush(p), true, true);
    }

    /** Undo a credit recorded by mistake: the employee no longer sees the payslip. */
    @Transactional
    public PayslipView unmarkPaid(AuthUser me, Long id) {
        Payslip p = full(id);
        Employee e = access.visible(me, p.getEmployee().getId());
        access.requireEditable(me, e);
        p.setPaidOn(null);
        p.setPaymentMode(null);
        p.setPaymentRef(null);
        p.setPaymentRemarks(null);
        p.setPaidRecordedBy(null);
        return view(payslipRepository.saveAndFlush(p), true, true);
    }

    /** Credits these published payslips the same day the same way; drafts, credited ones and the person's own are left out. */
    @Transactional
    public int markPaid(AuthUser me, BulkPayment body) {
        int done = 0;
        for (Long id : body.getIds()) {
            Payslip p = full(id);
            Employee e = access.visible(me, p.getEmployee().getId());
            if (p.getStatus() == PayslipStatus.PUBLISHED && p.getPaidOn() == null && access.editable(me, e)) {
                credit(me, p, body.getPaidOn(), body.getMode(), body.getReference(), body.getRemarks());
                done++;
            }
        }
        return done;
    }

    private void credit(AuthUser me, Payslip p, LocalDate paidOn, PaymentMode mode, String reference, String remarks) {
        if (p.getStatus() != PayslipStatus.PUBLISHED) {
            throw ApiException.conflict("Publish the payslip first, then record the salary as credited");
        }
        YearMonth ym = YearMonth.of(p.getPeriodYear(), p.getPeriodMonth());
        if (paidOn.isBefore(ym.atDay(1))) {
            throw ApiException.badRequest("The credit date cannot be before " + label(ym));
        }
        if (paidOn.isAfter(LocalDate.now(ZoneId.of(zone)))) {
            throw ApiException.badRequest("The credit date cannot be in the future");
        }
        p.setPaidOn(paidOn);
        p.setPaymentMode(mode);
        p.setPaymentRef(reference == null || reference.isBlank() ? null : reference.strip());
        p.setPaymentRemarks(remarks == null || remarks.isBlank() ? null : remarks.strip());
        p.setPaidRecordedBy(employeeRepository.getReferenceById(me.id()));
    }

    @Transactional
    public PayslipView unpublish(AuthUser me, Long id) {
        Payslip p = full(id);
        Employee e = access.visible(me, p.getEmployee().getId());
        access.requireEditable(me, e);
        if (p.getPaidOn() != null) {
            throw ApiException.conflict("The salary is recorded as credited; undo the credit first");
        }
        p.setStatus(PayslipStatus.DRAFT);
        p.setPublishedAt(null);
        p.setPublishedBy(null);
        p.setUpdatedBy(employeeRepository.getReferenceById(me.id()));
        return view(payslipRepository.saveAndFlush(p), true, true);
    }

    @Transactional
    public void delete(AuthUser me, Long id) {
        Payslip p = full(id);
        Employee e = access.visible(me, p.getEmployee().getId());
        access.requireEditable(me, e);
        if (p.getStatus() == PayslipStatus.PUBLISHED) {
            throw ApiException.conflict("A published payslip cannot be deleted; move it back to draft first");
        }
        payslipRepository.delete(p);
    }

    // ---- the employee: own pay ----

    @Transactional(readOnly = true)
    public MyPay myPay(AuthUser me, Integer fyOrNull) {
        int fy = fyOrNull == null ? currentFy() : fyOrNull;
        SalaryView salary = payrollService.salary(me, me.id(), fy);
        List<Payslip> mine = payslipRepository.ofEmployeeYear(me.id(), fy).stream().filter(PayslipService::seenByEmployee).toList();

        BigDecimal earned = ZERO;
        BigDecimal received = ZERO;
        BigDecimal taxes = ZERO;
        Map<YearMonth, Payslip> byMonth = new HashMap<>();
        List<Summary> summaries = new ArrayList<>();
        for (Payslip p : mine) {
            Money m = money(p.getLines());
            earned = earned.add(m.gross());
            received = received.add(m.net());
            taxes = taxes.add(tax(p.getLines()));
            YearMonth ym = YearMonth.of(p.getPeriodYear(), p.getPeriodMonth());
            byMonth.put(ym, p);
            summaries.add(new Summary(p.getId(), p.getPeriodYear(), p.getPeriodMonth(), label(ym), p.getPayDate(),
                    p.getPaidDays(), p.getLopDays(), m, p.getPaidOn(), p.getPaymentMode()));
        }
        Collections.reverse(summaries); // newest first

        List<MonthPay> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            YearMonth ym = YearMonth.of(fy, 4).plusMonths(i);
            Payslip p = byMonth.get(ym);
            Money m = p == null ? null : money(p.getLines());
            months.add(new MonthPay(ym.getYear(), ym.getMonthValue(), ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    p == null ? null : p.getId(), m == null ? null : m.gross(), m == null ? null : m.net(),
                    p == null ? null : tax(p.getLines())));
        }

        BigDecimal expectedGross = salary.set() ? salary.totals().yearGross() : ZERO;
        BigDecimal expectedNet = salary.set() ? salary.totals().yearNet() : ZERO;
        BigDecimal monthlyTax = salary.lines().stream().filter(l -> l.tax()).map(l -> l.monthly()).reduce(ZERO, BigDecimal::add);
        BigDecimal expectedTax = salary.set() ? monthlyTax.multiply(BigDecimal.valueOf(salary.totals().monthsInYear())) : ZERO;
        BigDecimal balance = expectedGross.subtract(earned).max(ZERO);
        Dashboard d = new Dashboard(expectedGross, expectedNet, expectedTax, earned, received, taxes, balance, mine.size(),
                salary.set() ? salary.totals().monthsInYear() : 0);

        TreeSet<Integer> years = new TreeSet<>(Comparator.reverseOrder());
        years.addAll(payslipRepository.publishedYears(me.id()));
        years.add(currentFy());
        years.add(fy);
        return new MyPay(fy, PayrollService.label(fy), List.copyOf(years), salary, d, months, summaries);
    }

    /** The person's own payslip, once published and credited. */
    @Transactional(readOnly = true)
    public PayslipView myPayslip(AuthUser me, Long id) {
        Payslip p = full(id);
        if (!p.getEmployee().getId().equals(me.id()) || !seenByEmployee(p)) {
            throw new ResourceNotFoundException("Payslip not found: " + id);
        }
        return view(p, false, false);
    }

    /** This month's income tax for the amounts being typed on a draft (nothing saved); null for a Manual tax salary. */
    @Transactional(readOnly = true)
    public PayslipTax taxPreview(AuthUser me, Long id, TaxPreview body) {
        Payslip p = full(id);
        Employee e = access.visible(me, p.getEmployee().getId());
        access.requireEditable(me, e);
        Map<Long, BigDecimal> typed = new HashMap<>();
        for (LineAmount la : body.getLines()) {
            typed.put(la.getId(), la.getAmount());
        }
        SalaryStructure s = salaryRepository.find(e.getId(), p.getFyStartYear()).orElse(null);
        return workOutTax(p, s, l -> typed.getOrDefault(l.getId(), l.getAmount()));
    }

    // ---- income tax ----

    /** Puts this month's income tax in the payslip's Income Tax line (New or Old regime; Manual keeps what is typed). */
    private void applyTax(Payslip p, SalaryStructure s) {
        PayslipTax t = workOutTax(p, s, PayslipLine::getAmount);
        if (t != null) {
            taxLine(p.getLines()).setAmount(t.monthTax());
        }
    }

    /**
     * This month's income tax under the salary's regime, as payroll does monthly TDS: the year's income expected =
     * the earlier months of the year (their payslips; a month with none counts at the salary's amounts) + this month
     * + the months to come at the salary's amounts; its tax under the regime, less the tax of the earlier months,
     * spread over this month and the months to come. Loss of pay, a part month or a changed amount so changes the tax.
     * Null when the salary's tax is typed in (Manual) or the payslip has no Income Tax line.
     */
    private PayslipTax workOutTax(Payslip p, SalaryStructure s, Function<PayslipLine, BigDecimal> amount) {
        if (s == null || s.regime() == TaxRegime.MANUAL || taxLine(p.getLines()) == null) {
            return null;
        }
        YearMonth ym = YearMonth.of(p.getPeriodYear(), p.getPeriodMonth());
        int fy = p.getFyStartYear();
        BigDecimal salaryGross = ZERO;
        BigDecimal salaryPt = ZERO;
        BigDecimal salaryTax = ZERO;
        for (SalaryLine l : s.getLines()) {
            PayComponent c = l.getComponent();
            if (c.getKind() == PayComponentKind.EARNING) {
                salaryGross = salaryGross.add(l.getMonthlyAmount());
            } else if (isPt(c.getCode())) {
                salaryPt = salaryPt.add(l.getMonthlyAmount());
            } else if (isTds(c.getCode())) {
                salaryTax = salaryTax.add(l.getMonthlyAmount());
            }
        }
        Map<YearMonth, Payslip> made = new HashMap<>();
        for (Payslip other : payslipRepository.ofEmployeeYear(p.getEmployee().getId(), fy)) {
            if (!Objects.equals(other.getId(), p.getId())) {
                made.put(YearMonth.of(other.getPeriodYear(), other.getPeriodMonth()), other);
            }
        }
        BigDecimal income = ZERO;
        BigDecimal pt = ZERO;
        BigDecimal before = ZERO;
        YearMonth first = YearMonth.from(s.getEffectiveFrom()).isAfter(YearMonth.of(fy, 4)) ? YearMonth.from(s.getEffectiveFrom()) : YearMonth.of(fy, 4);
        // the earlier months: as paid, or at the salary's amounts when no payslip was made
        for (YearMonth m = YearMonth.of(fy, 4); m.isBefore(ym); m = m.plusMonths(1)) {
            Payslip earlier = made.get(m);
            if (earlier != null) {
                for (PayslipLine l : earlier.getLines()) {
                    if (l.getKind() == PayComponentKind.EARNING) {
                        income = income.add(l.getAmount());
                    } else if (isPt(l.getCode())) {
                        pt = pt.add(l.getAmount());
                    } else if (isTds(l.getCode())) {
                        before = before.add(l.getAmount());
                    }
                }
            } else if (!m.isBefore(first)) {
                income = income.add(salaryGross);
                pt = pt.add(salaryPt);
                before = before.add(salaryTax);
            }
        }
        // this month
        for (PayslipLine l : p.getLines()) {
            if (l.getKind() == PayComponentKind.EARNING) {
                income = income.add(nz(amount.apply(l)));
            } else if (isPt(l.getCode())) {
                pt = pt.add(nz(amount.apply(l)));
            }
        }
        // the months to come, at the salary's amounts
        YearMonth next = ym.plusMonths(1).isBefore(first) ? first : ym.plusMonths(1);
        int left = Math.max(0, (int) ChronoUnit.MONTHS.between(next, YearMonth.of(fy + 1, 3)) + 1);
        income = income.add(salaryGross.multiply(BigDecimal.valueOf(left)));
        pt = pt.add(salaryPt.multiply(BigDecimal.valueOf(left)));

        IncomeTax.Calc year = IncomeTax.calc(s.regime(), fy, income, pt, s.getTaxDeclared(), 1);
        BigDecimal month = year.annualTax().subtract(before).max(BigDecimal.ZERO)
                .divide(BigDecimal.valueOf(left + 1L), 0, RoundingMode.HALF_UP).setScale(2);
        return new PayslipTax(s.regime(), year.rules(), year.income(), year.annualTax(), before.setScale(2, RoundingMode.HALF_UP), left, month);
    }

    private static PayslipLine taxLine(Collection<PayslipLine> lines) {
        return lines.stream().filter(l -> l.getKind() == PayComponentKind.DEDUCTION && isTds(l.getCode())).findFirst().orElse(null);
    }

    /** The Income Tax component's code, as on salaries. */
    private static boolean isTds(String code) {
        return "TDS".equalsIgnoreCase(code);
    }

    /** Professional tax (taken off the income under the old regime). */
    private static boolean isPt(String code) {
        return "PT".equalsIgnoreCase(code);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    // ---- making and showing ----

    /** The lines from the salary: earnings for the paid days, deductions and reimbursements as they are. */
    private void fill(Payslip p, SalaryStructure s, YearMonth ym, int workingDays, int lopDays) {
        int employed = employedDays(s.getEffectiveFrom(), ym, workingDays);
        int lop = Math.min(lopDays, employed);
        int paid = employed - lop;
        p.setDaysInMonth(ym.lengthOfMonth());
        p.setWorkingDays(workingDays);
        p.setEmployedDays(employed);
        p.setLopDays(lop);
        p.setPaidDays(paid);
        List<SalaryLine> sorted = s.getLines().stream()
                .sorted(Comparator.comparing((SalaryLine l) -> l.getComponent().getKind().ordinal())
                        .thenComparingInt(l -> l.getComponent().getSortOrder()))
                .toList();
        int order = 0;
        for (SalaryLine sl : sorted) {
            PayComponent c = sl.getComponent();
            PayslipLine l = new PayslipLine();
            l.setPayslip(p);
            l.setComponent(c);
            l.setCode(c.getCode());
            l.setName(c.getName());
            l.setKind(c.getKind());
            l.setTax(c.isTax());
            l.setSortOrder(order += 10);
            l.setFullAmount(sl.getMonthlyAmount());
            l.setAmount(c.getKind() == PayComponentKind.EARNING ? forDays(sl.getMonthlyAmount(), paid, workingDays) : sl.getMonthlyAmount());
            p.getLines().add(l);
        }
    }

    /**
     * Working days employed this month: all of them, or for a salary starting in this month, the days from the start
     * that are not weekly offs (no more than the month's working days).
     */
    int employedDays(LocalDate start, YearMonth ym, int workingDays) {
        if (!start.isAfter(ym.atDay(1))) {
            return workingDays;
        }
        int count = 0;
        for (LocalDate d = start; !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            if (!weeklyOffs.contains(d.getDayOfWeek())) {
                count++;
            }
        }
        return Math.min(count, workingDays);
    }

    /** Pay for the paid days: monthly / working days x paid days, to the rupee (the full amount for a full month). */
    static BigDecimal forDays(BigDecimal monthly, int paid, int days) {
        if (paid >= days) {
            return monthly.setScale(2, RoundingMode.HALF_UP);
        }
        return monthly.multiply(BigDecimal.valueOf(paid)).divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP).setScale(2);
    }

    /** The employee sees a payslip once it is published and the salary is credited. */
    private static boolean seenByEmployee(Payslip p) {
        return p.getStatus() == PayslipStatus.PUBLISHED && p.getPaidOn() != null;
    }

    private void publishOne(AuthUser me, Payslip p) {
        p.setStatus(PayslipStatus.PUBLISHED);
        p.setPublishedAt(Instant.now());
        p.setPublishedBy(employeeRepository.getReferenceById(me.id()));
    }

    /**
     * The payslip with year-to-date amounts per line: this payslip plus the person's published payslips of the year
     * up to this month (for a draft, payroll sees its year to date as if it were published). For the employee, only
     * what they can see counts (published and credited).
     */
    private PayslipView view(Payslip p, boolean editable, boolean forPayroll) {
        int key = p.getPeriodYear() * 12 + p.getPeriodMonth();
        Map<String, BigDecimal> ytd = new HashMap<>();
        for (Payslip other : payslipRepository.ofEmployeeYear(p.getEmployee().getId(), p.getFyStartYear())) {
            if (other.getId().equals(p.getId()) || other.getStatus() != PayslipStatus.PUBLISHED
                    || (!forPayroll && other.getPaidOn() == null)
                    || other.getPeriodYear() * 12 + other.getPeriodMonth() > key) {
                continue;
            }
            other.getLines().forEach(l -> ytd.merge(l.getCode(), l.getAmount(), BigDecimal::add));
        }
        List<LineView> lines = p.getLines().stream()
                .map(l -> new LineView(l.getId(), l.getCode(), l.getName(), l.getKind(), l.isTax(), l.getFullAmount(),
                        l.getAmount(), ytd.getOrDefault(l.getCode(), ZERO).add(l.getAmount())))
                .toList();
        Money m = money(p.getLines());
        YearMonth ym = YearMonth.of(p.getPeriodYear(), p.getPeriodMonth());
        return new PayslipView(p.getId(), Person.of(p.getEmployee()),
                p.getDesignationName() != null ? p.getDesignationName()
                        : p.getEmployee().getDesignation() == null ? null : p.getEmployee().getDesignation().getName(),
                p.getPeriodYear(), p.getPeriodMonth(), label(ym), p.getFyStartYear(), p.getStatus(), p.getPayDate(),
                p.getDaysInMonth(), p.getWorkingDays(), p.getEmployedDays(), p.getLopDays(), p.getPaidDays(), lines, m,
                RupeesInWords.of(m.net()),
                company(p.getEmployee().getBrand()), forPayroll && editable,
                p.getPublishedBy() == null ? null : p.getPublishedBy().getName(), p.getPublishedAt(), p.getUpdatedAt(),
                p.getPaidOn(), p.getPaymentMode(), p.getPaymentRef(), forPayroll ? p.getPaymentRemarks() : null,
                p.getPaidRecordedBy() == null ? null : p.getPaidRecordedBy().getName(),
                forPayroll && p.getStatus() == PayslipStatus.DRAFT
                        ? workOutTax(p, salaryRepository.find(p.getEmployee().getId(), p.getFyStartYear()).orElse(null), PayslipLine::getAmount)
                        : null);
    }

    /** The person's brand: its name, address, GST number and logo; the company settings fill in what is missing. */
    private Company company(Brand b) {
        if (b == null) {
            return new Company(companyName, blank(companyAddress), null, null, null, null, null, "INR", "₹");
        }
        return new Company(b.getName(), b.getAddress() != null ? b.getAddress() : blank(companyAddress), b.getGstNumber(),
                b.getId(), b.getLogoUpdatedAt(), b.getContactNumber(), b.getEmail(), b.getCurrency().name(), b.getCurrency().symbol());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Money money(Collection<PayslipLine> lines) {
        BigDecimal gross = ZERO;
        BigDecimal deductions = ZERO;
        BigDecimal reimbursements = ZERO;
        for (PayslipLine l : lines) {
            switch (l.getKind()) {
                case EARNING -> gross = gross.add(l.getAmount());
                case DEDUCTION -> deductions = deductions.add(l.getAmount());
                case REIMBURSEMENT -> reimbursements = reimbursements.add(l.getAmount());
            }
        }
        return new Money(gross, deductions, reimbursements, gross.subtract(deductions).add(reimbursements));
    }

    private static Money sum(List<Money> all) {
        BigDecimal g = ZERO, d = ZERO, r = ZERO, n = ZERO;
        for (Money m : all) {
            g = g.add(m.gross());
            d = d.add(m.deductions());
            r = r.add(m.reimbursements());
            n = n.add(m.net());
        }
        return new Money(g, d, r, n);
    }

    private static BigDecimal tax(Collection<PayslipLine> lines) {
        return lines.stream().filter(l -> l.isTax() && l.getKind() == PayComponentKind.DEDUCTION)
                .map(PayslipLine::getAmount).reduce(ZERO, BigDecimal::add);
    }

    private Payslip full(Long id) {
        return payslipRepository.findFull(id).orElseThrow(() -> new ResourceNotFoundException("Payslip not found: " + id));
    }

    private static YearMonth yearMonth(int year, int month) {
        if (year < 2000 || year > 2100 || month < 1 || month > 12) {
            throw ApiException.badRequest("Unknown month " + year + "-" + month);
        }
        return YearMonth.of(year, month);
    }

    /** April 2025 to March 2026 -> 2025. */
    static int fyOf(YearMonth ym) {
        return ym.getMonthValue() >= 4 ? ym.getYear() : ym.getYear() - 1;
    }

    /** "September 2025". */
    static String label(YearMonth ym) {
        return ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
    }

    private int currentFy() {
        return fyOf(YearMonth.now(ZoneId.of(zone)));
    }
}
