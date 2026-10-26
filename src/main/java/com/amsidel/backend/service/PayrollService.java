package com.amsidel.backend.service;

import com.amsidel.backend.dto.PayrollRequests;
import com.amsidel.backend.dto.PayrollViews.*;
import com.amsidel.backend.entity.*;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.PayComponentRepository;
import com.amsidel.backend.repository.SalaryStructureRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Yearly payroll set-up: the pay components, and each employee's salary for a financial year (April to March).
 * Who sees whom: owners and people without a brand see everyone; anyone else with the Payroll menu sees the people
 * of their own brand. Nobody changes their own salary, except an owner.
 * This is the salary set-up only; payslips are not generated here.
 */
@Service
@RequiredArgsConstructor
public class PayrollService {

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    /** Working days in a month unless a salary says otherwise. */
    public static final int DEFAULT_WORKING_DAYS = 26;

    private final PayComponentRepository componentRepository;
    private final SalaryStructureRepository salaryRepository;
    private final EmployeeRepository employeeRepository;
    private final PayrollAccess access;
    private final MenuAccessService menuAccess;

    @Value("${app.leave.zone:Asia/Kolkata}")
    private String zone;

    // ---- pay components ----

    @Transactional(readOnly = true)
    public List<ComponentView> components() {
        return componentRepository.ordered().stream()
                .map(c -> ComponentView.of(c, componentRepository.inUse(c.getId()))).toList();
    }

    @Transactional
    public ComponentView saveComponent(Long id, PayrollRequests.Component body) {
        String code = body.getCode().strip().toUpperCase(Locale.ROOT);
        boolean taken = id == null ? componentRepository.existsByCodeIgnoreCase(code)
                : componentRepository.existsByCodeIgnoreCaseAndIdNot(code, id);
        if (taken) {
            throw ApiException.conflict("There is already a component with the code " + code);
        }
        PayComponent c = id == null ? new PayComponent() : component(id);
        if (id != null && c.getKind() != body.getKind() && componentRepository.inUse(id)) {
            throw ApiException.conflict(c.getName() + " is used in salaries, so it stays " + label(c.getKind())
                    + "; add a new component instead");
        }
        c.setCode(code);
        c.setName(body.getName().strip());
        c.setKind(body.getKind());
        c.setSortOrder(body.getSortOrder());
        c.setActive(body.isActive());
        c.setTax(body.isTax());
        c = componentRepository.save(c);
        return ComponentView.of(c, id != null && componentRepository.inUse(c.getId()));
    }

    /** Only a component no salary uses; otherwise make it inactive. */
    @Transactional
    public void deleteComponent(Long id) {
        PayComponent c = component(id);
        if (componentRepository.inUse(id)) {
            throw ApiException.conflict(c.getName() + " is used in salaries; make it inactive instead");
        }
        componentRepository.delete(c);
    }

    // ---- salaries ----

    /** Everyone in scope for a year (active people, and anyone who had a salary that year), with their totals. */
    @Transactional(readOnly = true)
    public YearView year(AuthUser me, Integer fyOrNull) {
        int fy = fyOrNull == null ? currentFy() : fy(fyOrNull);
        Map<Long, SalaryStructure> salaries = salaryRepository.ofYear(fy).stream()
                .collect(Collectors.toMap(s -> s.getEmployee().getId(), Function.identity()));
        List<SalaryRow> rows = new ArrayList<>();
        List<Totals> set = new ArrayList<>();
        for (Employee e : people(me)) {
            SalaryStructure s = salaries.get(e.getId());
            if (s == null && !e.isActive()) {
                continue;
            }
            Totals t = s == null ? totals(Map.of(), fy, start(fy), 0) : totals(s);
            if (s != null) {
                set.add(t);
            }
            rows.add(new SalaryRow(Person.of(e), s != null, s == null ? null : s.getEffectiveFrom(),
                    s == null ? DEFAULT_WORKING_DAYS : s.getWorkingDays(), t, editable(me, e),
                    s == null || s.getUpdatedBy() == null ? null : s.getUpdatedBy().getName(),
                    s == null ? null : s.getUpdatedAt(), s == null ? null : s.regime()));
        }
        return new YearView(fy, label(fy), years(), rows, sum(set), set.size());
    }

    @Transactional(readOnly = true)
    public SalaryView salary(AuthUser me, Long employeeId, int fyStart) {
        int fy = fy(fyStart);
        Employee e = visible(me, employeeId);
        Optional<SalaryStructure> s = salaryRepository.find(e.getId(), fy);
        return view(me, e, fy, s.orElse(null));
    }

    @Transactional
    public SalaryView save(AuthUser me, Long employeeId, int fyStart, PayrollRequests.Salary body) {
        int fy = fy(fyStart);
        Employee e = visible(me, employeeId);
        access.requireEditable(me, e);
        LocalDate from = body.getEffectiveFrom();
        if (from.isBefore(start(fy)) || from.isAfter(end(fy))) {
            throw ApiException.badRequest("Effective from must be inside " + label(fy) + " (" + start(fy) + " to " + end(fy) + ")");
        }
        Map<Long, PayComponent> components = componentRepository.findAll().stream()
                .collect(Collectors.toMap(PayComponent::getId, Function.identity()));
        Map<Long, BigDecimal> amounts = new LinkedHashMap<>();
        for (PayrollRequests.Line line : body.getLines()) {
            if (!components.containsKey(line.getComponentId())) {
                throw ApiException.badRequest("Unknown pay component " + line.getComponentId());
            }
            amounts.merge(line.getComponentId(), line.getMonthly().setScale(2, RoundingMode.HALF_UP), BigDecimal::add);
        }
        if (amounts.values().stream().noneMatch(a -> a.signum() > 0)) {
            throw ApiException.badRequest("Enter at least one amount");
        }

        SalaryStructure s = salaryRepository.find(e.getId(), fy).orElseGet(() -> {
            SalaryStructure n = new SalaryStructure();
            n.setEmployee(e);
            n.setFyStartYear(fy);
            return n;
        });
        // income tax: worked out under the new or old regime; typed in (Manual) only by someone allowed to
        // no regime sent: keep the salary's; a new one is typed in by someone allowed to, else the new regime
        TaxRegime regime = body.getTaxRegime() != null ? body.getTaxRegime() : s.getId() != null ? s.regime()
                : menuAccess.can(me, Menus.PAY_TAX_MANUAL) ? TaxRegime.MANUAL : TaxRegime.NEW;
        BigDecimal declared = body.getTaxDeclared() != null ? body.getTaxDeclared().setScale(2, RoundingMode.HALF_UP) : s.getTaxDeclared();
        PayComponent tds = taxComponent(components.values());
        if (tds != null) {
            BigDecimal before = s.getLines().stream().filter(l -> l.getComponent().getId().equals(tds.getId())).map(SalaryLine::getMonthlyAmount)
                    .findFirst().orElse(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            if (regime == TaxRegime.MANUAL) {
                BigDecimal asked = amounts.getOrDefault(tds.getId(), BigDecimal.ZERO);
                boolean changed = s.regime() != TaxRegime.MANUAL || s.getId() == null || asked.compareTo(before) != 0;
                if (changed && !menuAccess.can(me, Menus.PAY_TAX_MANUAL)) {
                    throw ApiException.forbidden("Typing the income tax by hand needs the Manual income tax access; choose the new or old regime");
                }
            } else {
                IncomeTax.Calc calc = taxFor(regime, fy, from, amounts, components, declared);
                amounts.put(tds.getId(), calc.monthlyTax());
            }
        }
        s.setTaxRegime(regime);
        s.setTaxDeclared(regime == TaxRegime.OLD ? declared : s.getTaxDeclared());
        s.setEffectiveFrom(from);
        s.setWorkingDays(body.getWorkingDays() == null ? DEFAULT_WORKING_DAYS : body.getWorkingDays());
        s.setNotes(body.getNotes() == null || body.getNotes().isBlank() ? null : body.getNotes().strip());
        s.setUpdatedBy(employeeRepository.getReferenceById(me.id()));
        if (body.getPan() != null) {
            e.setPanNumber(body.getPan().isBlank() ? null : body.getPan().strip().toUpperCase(Locale.ROOT));
        }
        if (body.getBankAccount() != null) {
            e.setBankAccountNumber(body.getBankAccount().isBlank() ? null : body.getBankAccount().strip());
        }
        if (body.getIfsc() != null) {
            e.setIfscCode(body.getIfsc().isBlank() ? null : body.getIfsc().strip().toUpperCase(Locale.ROOT));
        }
        if (body.getUpi() != null) {
            e.setUpiId(body.getUpi().isBlank() ? null : body.getUpi().strip().toLowerCase(Locale.ROOT));
        }
        // keep the lines that stay (same ids), drop the ones now 0, add the new ones
        Map<Long, SalaryLine> existing = s.getLines().stream()
                .collect(Collectors.toMap(l -> l.getComponent().getId(), Function.identity()));
        s.getLines().removeIf(l -> amounts.getOrDefault(l.getComponent().getId(), BigDecimal.ZERO).signum() == 0);
        amounts.forEach((componentId, amount) -> {
            if (amount.signum() == 0) {
                return;
            }
            SalaryLine l = existing.get(componentId);
            if (l == null) {
                l = new SalaryLine();
                l.setStructure(s);
                l.setComponent(components.get(componentId));
                s.getLines().add(l);
            }
            l.setMonthlyAmount(amount);
        });
        return view(me, e, fy, salaryRepository.saveAndFlush(s));
    }

    @Transactional
    public void delete(AuthUser me, Long employeeId, int fyStart) {
        int fy = fy(fyStart);
        Employee e = visible(me, employeeId);
        access.requireEditable(me, e);
        salaryRepository.find(e.getId(), fy).ifPresent(salaryRepository::delete);
    }

    // ---- rules and helpers ----

    private List<Employee> people(AuthUser me) {
        return access.people(me);
    }

    private Employee visible(AuthUser me, Long employeeId) {
        return access.visible(me, employeeId);
    }

    private boolean editable(AuthUser me, Employee e) {
        return access.editable(me, e);
    }

    private SalaryView view(AuthUser me, Employee e, int fy, SalaryStructure s) {
        Map<Long, BigDecimal> amounts = s == null ? Map.of() : s.getLines().stream()
                .collect(Collectors.toMap(l -> l.getComponent().getId(), SalaryLine::getMonthlyAmount));
        List<LineView> lines = componentRepository.ordered().stream()
                .filter(c -> c.isActive() || amounts.containsKey(c.getId()))
                .map(c -> {
                    BigDecimal m = amounts.getOrDefault(c.getId(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                    return new LineView(c.getId(), c.getCode(), c.getName(), c.getKind(), c.isActive(), c.isTax(), m,
                            m.multiply(TWELVE));
                }).toList();
        // a new salary starts on 1 April, or on the joining date when they joined later in the year
        LocalDate from = s != null ? s.getEffectiveFrom()
                : e.getJoinedDate() != null && e.getJoinedDate().isAfter(start(fy)) && !e.getJoinedDate().isAfter(end(fy))
                ? e.getJoinedDate() : start(fy);
        Totals t = s == null ? totals(Map.of(), fy, from, 0) : totals(s);
        return new SalaryView(Person.of(e), fy, label(fy), start(fy), end(fy), s != null, from,
                s == null ? DEFAULT_WORKING_DAYS : s.getWorkingDays(),
                s == null ? null : s.getNotes(), lines, t, editable(me, e),
                s == null || s.getUpdatedBy() == null ? null : s.getUpdatedBy().getName(), s == null ? null : s.getUpdatedAt(),
                taxView(me, fy, from, s, amounts));
    }

    private Totals totals(SalaryStructure s) {
        Map<PayComponentKind, BigDecimal> byKind = new EnumMap<>(PayComponentKind.class);
        for (SalaryLine l : s.getLines()) {
            byKind.merge(l.getComponent().getKind(), l.getMonthlyAmount(), BigDecimal::add);
        }
        return totals(byKind, s.getFyStartYear(), s.getEffectiveFrom(), 1);
    }

    private Totals totals(Map<PayComponentKind, BigDecimal> byKind, int fy, LocalDate from, int any) {
        BigDecimal gross = money(byKind.get(PayComponentKind.EARNING));
        BigDecimal deductions = money(byKind.get(PayComponentKind.DEDUCTION));
        BigDecimal reimbursements = money(byKind.get(PayComponentKind.REIMBURSEMENT));
        BigDecimal net = gross.subtract(deductions).add(reimbursements);
        int months = any == 0 ? 0 : monthsPaid(fy, from);
        BigDecimal m = BigDecimal.valueOf(months);
        return new Totals(gross, deductions, reimbursements, net, gross.multiply(TWELVE), deductions.multiply(TWELVE),
                reimbursements.multiply(TWELVE), net.multiply(TWELVE), months, gross.multiply(m), net.multiply(m));
    }

    /** Totals of the whole payroll (months is not meaningful there: 0). */
    private static Totals sum(List<Totals> all) {
        BigDecimal[] t = new BigDecimal[10];
        Arrays.fill(t, BigDecimal.ZERO.setScale(2));
        for (Totals x : all) {
            t[0] = t[0].add(x.monthlyGross());
            t[1] = t[1].add(x.monthlyDeductions());
            t[2] = t[2].add(x.monthlyReimbursements());
            t[3] = t[3].add(x.monthlyNet());
            t[4] = t[4].add(x.annualGross());
            t[5] = t[5].add(x.annualDeductions());
            t[6] = t[6].add(x.annualReimbursements());
            t[7] = t[7].add(x.annualNet());
            t[8] = t[8].add(x.yearGross());
            t[9] = t[9].add(x.yearNet());
        }
        return new Totals(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], 0, t[8], t[9]);
    }

    /** Whole months paid in the year, counting the month the salary starts in: from September = 7 (Sep to Mar). */
    static int monthsPaid(int fy, LocalDate from) {
        LocalDate first = from.isBefore(start(fy)) ? start(fy) : from.withDayOfMonth(1);
        if (first.isAfter(end(fy))) {
            return 0;
        }
        return (int) ChronoUnit.MONTHS.between(first, end(fy).plusDays(1));
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    // ---- income tax ----

    /** The Income Tax component (code TDS) the worked-out tax goes in; null when there is none. */
    private static PayComponent taxComponent(Collection<PayComponent> all) {
        return all.stream().filter(c -> "TDS".equalsIgnoreCase(c.getCode())).findFirst().orElse(null);
    }

    /** The tax on a salary's year: gross earnings and professional tax over the months it is paid in the year. */
    private static IncomeTax.Calc taxFor(TaxRegime regime, int fy, LocalDate from, Map<Long, BigDecimal> amounts, Map<Long, PayComponent> components,
                                         BigDecimal declared) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal pt = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> a : amounts.entrySet()) {
            PayComponent c = components.get(a.getKey());
            if (c == null) {
                continue;
            }
            if (c.getKind() == PayComponentKind.EARNING) {
                gross = gross.add(a.getValue());
            } else if ("PT".equalsIgnoreCase(c.getCode())) {
                pt = pt.add(a.getValue());
            }
        }
        int months = monthsPaid(fy, from);
        BigDecimal m = BigDecimal.valueOf(months);
        return IncomeTax.calc(regime, fy, gross.multiply(m), pt.multiply(m), declared, months);
    }

    private TaxView taxView(AuthUser me, int fy, LocalDate from, SalaryStructure s, Map<Long, BigDecimal> amounts) {
        Map<Long, PayComponent> components = componentRepository.findAll().stream().collect(Collectors.toMap(PayComponent::getId, Function.identity()));
        TaxRegime regime = s == null ? TaxRegime.NEW : s.regime();
        BigDecimal declared = s == null ? null : s.getTaxDeclared();
        IncomeTax.Calc newer = taxFor(TaxRegime.NEW, fy, from, amounts, components, null);
        IncomeTax.Calc older = taxFor(TaxRegime.OLD, fy, from, amounts, components, declared);
        PayComponent tds = taxComponent(components.values());
        return new TaxView(regime, declared, regime == TaxRegime.NEW ? newer : regime == TaxRegime.OLD ? older : null, newer, older,
                menuAccess.can(me, Menus.PAY_TAX_MANUAL), tds == null ? null : tds.getName());
    }

    /** Both regimes for a salary still being typed. */
    public Map<String, IncomeTax.Calc> taxPreview(int fyStart, PayrollRequests.TaxPreview body) {
        int fy = fy(fyStart);
        int months = monthsPaid(fy, body.getEffectiveFrom());
        BigDecimal m = BigDecimal.valueOf(months);
        BigDecimal gross = body.getMonthlyGross().multiply(m);
        BigDecimal pt = (body.getMonthlyProfessionalTax() == null ? BigDecimal.ZERO : body.getMonthlyProfessionalTax()).multiply(m);
        Map<String, IncomeTax.Calc> out = new LinkedHashMap<>();
        out.put("NEW", IncomeTax.calc(TaxRegime.NEW, fy, gross, pt, null, months));
        out.put("OLD", IncomeTax.calc(TaxRegime.OLD, fy, gross, pt, body.getDeclared(), months));
        return out;
    }

    private PayComponent component(Long id) {
        return componentRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Pay component not found: " + id));
    }

    private List<Integer> years() {
        TreeSet<Integer> years = new TreeSet<>(Comparator.reverseOrder());
        years.addAll(salaryRepository.years());
        int now = currentFy();
        years.add(now - 1);
        years.add(now);
        years.add(now + 1);
        return List.copyOf(years);
    }

    private int currentFy() {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        return today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
    }

    private static int fy(int fyStart) {
        if (fyStart < 2000 || fyStart > 2100) {
            throw ApiException.badRequest("Unknown financial year " + fyStart);
        }
        return fyStart;
    }

    private static LocalDate start(int fy) {
        return LocalDate.of(fy, 4, 1);
    }

    private static LocalDate end(int fy) {
        return LocalDate.of(fy + 1, 3, 31);
    }

    /** 2025 -> "FY 2025-26". */
    static String label(int fy) {
        return "FY " + fy + "-" + String.format("%02d", (fy + 1) % 100);
    }

    private static String label(PayComponentKind kind) {
        return switch (kind) {
            case EARNING -> "an earning";
            case DEDUCTION -> "a deduction";
            case REIMBURSEMENT -> "a reimbursement";
        };
    }
}
