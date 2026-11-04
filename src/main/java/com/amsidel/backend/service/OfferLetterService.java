/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.OfferLetterDtos.Body;
import com.amsidel.backend.dto.OfferLetterDtos.Defaults;
import com.amsidel.backend.dto.OfferLetterDtos.Page;
import com.amsidel.backend.dto.OfferLetterDtos.PdfFile;
import com.amsidel.backend.dto.OfferLetterDtos.Row;
import com.amsidel.backend.dto.OfferLetterDtos.View;
import com.amsidel.backend.entity.Brand;
import com.amsidel.backend.entity.BrandLogo;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.OfferLetter;
import com.amsidel.backend.entity.OfferLetter.Status;
import com.amsidel.backend.entity.OfferLetterFile;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.BrandLogoRepository;
import com.amsidel.backend.repository.BrandRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.repository.OfferLetterFileRepository;
import com.amsidel.backend.repository.OfferLetterRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Offer letters: drafts change freely; issuing checks the letter is complete, gives it a number (DW/OL/2026/0001) and
 * keeps its PDF as it is. After that it is locked: changing needs the Change issued offer letter tick (the PDF is made
 * again and the change is recorded), deleting the Delete one. Someone with a brand sees only that brand's letters.
 */
@Service
@RequiredArgsConstructor
public class OfferLetterService {

    /** Until the brand has issued a letter of its own. HR changes them on the letter; they are not legal advice. */
    private static final String DEFAULT_CLAUSES = String.join("\n",
            "This offer depends on the details and documents you have given us being correct and complete.",
            "Please bring originals and copies of your identity proof, address proof, educational and experience certificates, and your last employer's relieving letter (if any) on your joining date.",
            "Report on your joining date. If you do not join on that date, and we have not agreed another date in writing, this offer lapses.",
            "Your salary is paid monthly, after the deductions the law requires. It is confidential.",
            "You will follow the company's rules, policies and working hours, as they are from time to time.",
            "You will keep the company's and its customers' information confidential, during and after your employment.",
            "You will not take up other paid work during your employment without the company's written permission.",
            "Probation, leave, notice period and ending of employment follow the company's policy, as confirmed in your appointment letter.");

    private final OfferLetterRepository letters;
    private final OfferLetterFileRepository files;
    private final BrandRepository brands;
    private final BrandLogoRepository logos;
    private final EmployeeRepository employees;
    private final MenuAccessService menuAccess;
    private final WorkRuleService workRules;
    private final OfferLetterPdf pdf;

    @Transactional(readOnly = true)
    public Page list(AuthUser me) {
        List<Row> rows = letters.listWithBrands().stream().filter(o -> inScope(me, o.getBrand())).map(Row::of).toList();
        return new Page(rows, menuAccess.can(me, Menus.OFFER_EDIT), menuAccess.can(me, Menus.OFFER_DELETE));
    }

    @Transactional(readOnly = true)
    public View get(AuthUser me, Long id) {
        return View.of(letter(me, id));
    }

    /** The brand's details and working hours; the introduction and clauses of its last letter, else a starting text. */
    @Transactional(readOnly = true)
    public Defaults defaults(AuthUser me, Long brandId) {
        Brand b = brand(me, brandId);
        OfferLetter last = letters.findFirstByBrandIdAndStatusOrderByIdDesc(brandId, Status.ISSUED).orElse(null);
        Employee mine = employees.findById(me.id()).orElse(null);
        return new Defaults(b.getName(), b.getAddress(),
                last != null && last.getCompanyIntro() != null ? last.getCompanyIntro() : defaultIntro(b),
                b.getContactNumber(), b.getEmail(), workRules.brandDefault(b).weeklyMinHours(),
                last != null && last.getClauses() != null ? last.getClauses() : DEFAULT_CLAUSES,
                mine == null ? null : mine.getName(),
                mine == null || mine.getDesignation() == null ? null : mine.getDesignation().getName(),
                b.getCurrency().symbol());
    }

    /** A new draft. */
    @Transactional
    public View create(AuthUser me, Body body) {
        OfferLetter o = new OfferLetter();
        o.setCreatedAt(Instant.now());
        o.setCreatedByName(employees.findById(me.id()).map(Employee::getName).orElse(me.userId()));
        apply(o, body, brand(me, body.getBrandId()));
        return View.of(letters.save(o));
    }

    /** A draft freely; an issued letter with the tick only, still complete, and with its PDF made again. */
    @Transactional
    public View update(AuthUser me, Long id, Body body) {
        OfferLetter o = letter(me, id);
        if (o.getStatus() == Status.DRAFT) {
            apply(o, body, brand(me, body.getBrandId()));
            return View.of(o);
        }
        if (!menuAccess.can(me, Menus.OFFER_EDIT)) {
            throw ApiException.forbidden("This offer letter is issued and cannot be changed");
        }
        // the number starts with the brand's code
        if (!Objects.equals(o.getBrand().getId(), body.getBrandId())) {
            throw ApiException.badRequest("An issued offer letter stays in its brand");
        }
        apply(o, body, o.getBrand());
        checkComplete(o);
        o.setChangedByName(employees.findById(me.id()).map(Employee::getName).orElse(me.userId()));
        o.setChangedAt(Instant.now());
        keepPdf(letters.saveAndFlush(o));
        return View.of(o);
    }

    /** Checks it is complete, gives it its number and keeps its PDF. From here on it is locked. */
    @Transactional
    public View issue(AuthUser me, Long id) {
        OfferLetter o = letter(me, id);
        if (o.getStatus() == Status.ISSUED) {
            throw ApiException.conflict("This offer letter is already issued");
        }
        checkComplete(o);
        int year = o.getIssueDate().getYear();
        int seq = letters.lastSeq(o.getBrand().getId(), year) + 1;
        o.setLetterYear(year);
        o.setLetterSeq(seq);
        o.setLetterNumber("%s/OL/%d/%04d".formatted(o.getBrand().getCode(), year, seq));
        o.setStatus(Status.ISSUED);
        o.setIssuedAt(Instant.now());
        keepPdf(letters.saveAndFlush(o));
        return View.of(o);
    }

    /** A draft freely; an issued letter with the tick only. */
    @Transactional
    public void delete(AuthUser me, Long id) {
        OfferLetter o = letter(me, id);
        if (o.getStatus() == Status.ISSUED && !menuAccess.can(me, Menus.OFFER_DELETE)) {
            throw ApiException.forbidden("This offer letter is issued and cannot be deleted");
        }
        files.deleteById(id);
        letters.delete(o);
    }

    /** An issued letter's PDF as it was issued; a draft's as it stands now, marked DRAFT. */
    @Transactional(readOnly = true)
    public PdfFile pdf(AuthUser me, Long id) {
        OfferLetter o = letter(me, id);
        if (o.getStatus() == Status.ISSUED) {
            byte[] kept = files.findById(id).map(OfferLetterFile::getContent).orElseGet(() -> render(o));
            return new PdfFile("Offer-Letter-" + o.getLetterNumber().replace('/', '-') + ".pdf", kept);
        }
        return new PdfFile("Offer-Letter-Draft-" + o.getId() + ".pdf", render(o));
    }

    // ---- inside ----

    private void keepPdf(OfferLetter o) {
        files.save(new OfferLetterFile(o.getId(), render(o)));
    }

    private byte[] render(OfferLetter o) {
        Brand b = o.getBrand();
        byte[] logo = b.getLogoUpdatedAt() == null ? null : logos.findById(b.getId()).map(BrandLogo::getContent).orElse(null);
        return pdf.render(o, logo);
    }

    private static void apply(OfferLetter o, Body b, Brand brand) {
        o.setBrand(brand);
        o.setIssueDate(b.getIssueDate());
        o.setCompanyName(clean(b.getCompanyName()));
        o.setCompanyAddress(clean(b.getCompanyAddress()));
        o.setCompanyIntro(clean(b.getCompanyIntro()));
        o.setCandidateName(clean(b.getCandidateName()));
        o.setCandidateAddress(clean(b.getCandidateAddress()));
        o.setDesignation(clean(b.getDesignation()));
        o.setJoiningDate(b.getJoiningDate());
        o.setStartDate(b.getStartDate());
        o.setWeeklyHours(b.getWeeklyHours());
        o.setAnnualPackage(b.getAnnualPackage());
        o.setMonthlySalary(b.getMonthlySalary());
        o.setContactNumber(clean(b.getContactNumber()));
        o.setContactEmail(clean(b.getContactEmail()));
        o.setClauses(clean(b.getClauses()));
        o.setIssuedByName(clean(b.getIssuedByName()));
        o.setIssuedByDesignation(clean(b.getIssuedByDesignation()));
        o.getLines().clear();
        if (b.getLines() != null) {
            b.getLines().forEach(l -> o.getLines().add(new OfferLetter.Line(l.getName().strip(), l.getAnnualAmount())));
        }
    }

    /** Monthly salary is the only part that may be empty; the split has to come to the package exactly. */
    private static void checkComplete(OfferLetter o) {
        List<String> missing = new ArrayList<>();
        add(missing, o.getIssueDate(), "issued date");
        add(missing, o.getCompanyName(), "company name");
        add(missing, o.getCompanyAddress(), "company address");
        add(missing, o.getCompanyIntro(), "about the company");
        add(missing, o.getDesignation(), "designation");
        add(missing, o.getJoiningDate(), "joining date");
        add(missing, o.getStartDate(), "start date");
        add(missing, o.getWeeklyHours(), "working hours in a week");
        add(missing, o.getAnnualPackage(), "annual package");
        add(missing, o.getLines().isEmpty() ? null : o.getLines(), "package split");
        add(missing, o.getContactNumber(), "contact number");
        add(missing, o.getContactEmail(), "contact email");
        add(missing, o.getClauses(), "clauses and rules");
        add(missing, o.getIssuedByName(), "issued by");
        add(missing, o.getIssuedByDesignation(), "issued by designation");
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("Before issuing, fill in: " + String.join(", ", missing));
        }
        if (o.getStartDate().isBefore(o.getJoiningDate())) {
            throw ApiException.badRequest("The start date cannot be before the joining date");
        }
        BigDecimal split = o.getLines().stream().map(OfferLetter.Line::getAnnualAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (split.compareTo(o.getAnnualPackage()) != 0) {
            throw ApiException.badRequest("The package split comes to " + split.stripTrailingZeros().toPlainString()
                    + " but the annual package is " + o.getAnnualPackage().stripTrailingZeros().toPlainString());
        }
        if (o.getMonthlySalary() != null && o.getMonthlySalary().compareTo(o.getAnnualPackage()) > 0) {
            throw ApiException.badRequest("The monthly salary cannot be more than the annual package");
        }
    }

    private static void add(List<String> missing, Object value, String name) {
        if (value == null) {
            missing.add(name);
        }
    }

    private static String defaultIntro(Brand b) {
        String what = b.getCategory() == null || b.getCategory().isBlank() ? "" : " in " + b.getCategory();
        return b.getName() + " is a business of the Amsidel group" + what + ". We are glad you are thinking of joining our team.";
    }

    private OfferLetter letter(AuthUser me, Long id) {
        OfferLetter o = letters.findById(id).orElseThrow(() -> new ResourceNotFoundException("Offer letter not found: " + id));
        if (!inScope(me, o.getBrand())) {
            throw new ResourceNotFoundException("Offer letter not found: " + id);
        }
        return o;
    }

    private Brand brand(AuthUser me, Long id) {
        Brand b = brands.findById(id).orElseThrow(() -> ApiException.badRequest("Brand not found: " + id));
        if (!inScope(me, b)) {
            throw ApiException.forbidden("You can only issue offer letters for your own brand");
        }
        return b;
    }

    private static boolean inScope(AuthUser me, Brand b) {
        return me.isOwner() || me.brandId() == null || me.brandId().equals(b.getId());
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
