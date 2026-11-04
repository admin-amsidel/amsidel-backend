/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.CurrencyCode;
import com.amsidel.backend.entity.OfferLetter;
import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * An offer letter as a PDF, on the brand's letterhead (logo, name, address, contact): the offer, the terms at a glance,
 * the package split, the clauses and rules, and places to sign. Page numbers on every page; a draft is marked DRAFT
 * across each page, and an issued letter carries its number.
 */
@Component
public class OfferLetterPdf {

    private static final Color INK = new Color(0x1f, 0x26, 0x30);
    private static final Color MUTED = new Color(0x5b, 0x68, 0x78);
    private static final Color LINE = new Color(0xdc, 0xe7, 0xf2);
    private static final Color BLUE = new Color(0x1d, 0x5f, 0xa3);
    private static final Color BLUE_SOFT = new Color(0xee, 0xf5, 0xfd);
    private static final Color STAMP = new Color(0xb3, 0x26, 0x1e);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final BigDecimal MONTHS = BigDecimal.valueOf(12);
    private static final String BLANK = "—";

    private final BaseFont regular;
    private final BaseFont bold;
    private final byte[] defaultLogo;

    public OfferLetterPdf() throws IOException {
        regular = font("payslip/fonts/DejaVuSans.ttf");
        bold = font("payslip/fonts/DejaVuSans-Bold.ttf");
        try (InputStream in = new ClassPathResource("payslip/logo.png").getInputStream()) {
            defaultLogo = in.readAllBytes();
        }
    }

    /** brandLogo: the brand's own logo, or null for the group logo. Call inside a transaction: reads the brand and lines. */
    public byte[] render(OfferLetter o, byte[] brandLogo) {
        CurrencyCode currency = o.getBrand().getCurrency();
        boolean draft = o.getStatus() == OfferLetter.Status.DRAFT;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 40, 54);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new Pages(draft ? "Draft, not issued" : o.getLetterNumber(), draft));
        doc.addTitle("Offer letter - " + o.getCandidateName());
        doc.addAuthor(text(o.getCompanyName(), o.getBrand().getName()));
        doc.open();

        doc.add(letterhead(o, brandLogo));
        doc.add(spaced(reference(o, draft), 12, 0));
        doc.add(spaced(para("OFFER OF EMPLOYMENT", bold, 13, BLUE), 14, 8));

        doc.add(para("To,", regular, 9.5f, MUTED));
        doc.add(para(o.getCandidateName(), bold, 10.5f, INK));
        if (o.getCandidateAddress() != null) {
            doc.add(para(o.getCandidateAddress(), regular, 9.5f, INK));
        }
        doc.add(spaced(para("Dear " + o.getCandidateName() + ",", regular, 9.5f, INK), 12, 0));
        String company = text(o.getCompanyName(), o.getBrand().getName());
        doc.add(spaced(body("We are pleased to offer you the position of " + text(o.getDesignation(), BLANK) + " at " + company
                + ", on the terms in this letter."), 6, 0));

        doc.add(heading("About " + company));
        doc.add(body(text(o.getCompanyIntro(), BLANK)));

        doc.add(spaced(terms(o, currency), 12, 0));
        doc.add(spaced(split(o, currency), 12, 0));

        doc.add(heading("Clauses and rules about joining"));
        clauses(doc, o);

        doc.add(spaced(body("If you have any questions about this offer, please contact us: "
                + String.join(" · ", Stream.of(o.getContactNumber(), o.getContactEmail()).filter(x -> x != null).toList()) + "."), 12, 0));
        doc.add(spaced(body("Please sign below to accept this offer. We look forward to welcoming you."), 6, 0));
        doc.add(spaced(signatures(o, company), 16, 0));
        doc.close();
        return out.toByteArray();
    }

    /** Logo on the left; the company's name, address and contact on the right; a line under them. */
    private PdfPTable letterhead(OfferLetter o, byte[] brandLogo) {
        PdfPTable t = table(new float[]{24, 76});
        PdfPCell logo = cell();
        logo.setBorder(Rectangle.BOTTOM);
        logo.setBorderColorBottom(BLUE);
        logo.setBorderWidthBottom(1.5f);
        logo.setPaddingBottom(8);
        logo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        try {
            Image img = Image.getInstance(brandLogo != null ? brandLogo : defaultLogo);
            img.scaleToFit(110, 54);
            img.setAlignment(Image.ALIGN_LEFT);
            logo.addElement(img);
        } catch (IOException e) {
            throw new IllegalStateException("The logo could not be read", e);
        }
        t.addCell(logo);

        PdfPCell who = cell();
        who.setBorder(Rectangle.BOTTOM);
        who.setBorderColorBottom(BLUE);
        who.setBorderWidthBottom(1.5f);
        who.setPaddingBottom(8);
        who.setVerticalAlignment(Element.ALIGN_MIDDLE);
        who.addElement(right(para(text(o.getCompanyName(), o.getBrand().getName()), bold, 14, BLUE)));
        if (o.getCompanyAddress() != null) {
            who.addElement(right(para(o.getCompanyAddress(), regular, 8.5f, MUTED)));
        }
        String contact = String.join(" · ", Stream.of(o.getContactNumber(), o.getContactEmail()).filter(x -> x != null).toList());
        if (!contact.isEmpty()) {
            who.addElement(right(para(contact, regular, 8.5f, MUTED)));
        }
        t.addCell(who);
        return t;
    }

    private PdfPTable reference(OfferLetter o, boolean draft) {
        PdfPTable t = table(new float[]{60, 40});
        PdfPCell ref = cell();
        ref.addElement(para("Ref: " + (draft ? "Draft, not issued" : o.getLetterNumber()), regular, 9, MUTED));
        t.addCell(ref);
        PdfPCell date = cell();
        date.addElement(right(para("Date: " + date(o.getIssueDate()), regular, 9, MUTED)));
        t.addCell(date);
        return t;
    }

    /** A section's heading as the table's first row, so that the two always stay on one page. */
    private void title(PdfPTable t, String heading) {
        t.setKeepTogether(true);
        PdfPCell c = cell();
        c.setColspan(t.getNumberOfColumns());
        c.setPaddingLeft(0);
        c.setPaddingBottom(5);
        c.addElement(para(heading, bold, 10.5f, BLUE));
        t.addCell(c);
    }

    /** The terms at a glance: label on the left, value on the right. */
    private PdfPTable terms(OfferLetter o, CurrencyCode currency) {
        PdfPTable t = table(new float[]{36, 64});
        title(t, "Your offer");
        row(t, "Designation", text(o.getDesignation(), BLANK));
        row(t, "Joining date", date(o.getJoiningDate()));
        row(t, "Start date", date(o.getStartDate()));
        row(t, "Working hours", o.getWeeklyHours() == null ? BLANK : o.getWeeklyHours().stripTrailingZeros().toPlainString() + " hours a week");
        row(t, "Annual package", o.getAnnualPackage() == null ? BLANK : MoneyText.format(o.getAnnualPackage(), currency));
        if (o.getMonthlySalary() != null) {
            row(t, "Monthly salary", MoneyText.format(o.getMonthlySalary(), currency));
        }
        return t;
    }

    private void row(PdfPTable t, String label, String value) {
        PdfPCell l = lined();
        l.setBackgroundColor(BLUE_SOFT);
        l.addElement(para(label, bold, 9, INK));
        t.addCell(l);
        PdfPCell v = lined();
        v.addElement(para(value, regular, 9, INK));
        t.addCell(v);
    }

    /** Each part with a month's and a year's amount; the monthly column is the year's divided by 12. */
    private PdfPTable split(OfferLetter o, CurrencyCode currency) {
        PdfPTable t = table(new float[]{50, 25, 25});
        title(t, "Package split");
        t.addCell(head("Component", false));
        t.addCell(head("Monthly", true));
        t.addCell(head("Annual", true));
        List<OfferLetter.Line> lines = o.getLines();
        BigDecimal total = BigDecimal.ZERO;
        for (OfferLetter.Line l : lines) {
            total = total.add(l.getAnnualAmount());
            t.addCell(value(l.getName(), false, regular, null));
            t.addCell(value(MoneyText.format(month(l.getAnnualAmount()), currency), true, regular, null));
            t.addCell(value(MoneyText.format(l.getAnnualAmount(), currency), true, regular, null));
        }
        t.addCell(value("Total", false, bold, BLUE_SOFT));
        t.addCell(value(MoneyText.format(month(total), currency), true, bold, BLUE_SOFT));
        t.addCell(value(MoneyText.format(total, currency), true, bold, BLUE_SOFT));
        return t;
    }

    private static BigDecimal month(BigDecimal year) {
        return year.divide(MONTHS, 2, RoundingMode.HALF_UP);
    }

    private PdfPCell head(String s, boolean alignRight) {
        PdfPCell c = lined();
        c.setBackgroundColor(BLUE);
        Paragraph p = para(s, bold, 9, Color.WHITE);
        c.addElement(alignRight ? right(p) : p);
        return c;
    }

    private PdfPCell value(String s, boolean alignRight, BaseFont f, Color fill) {
        PdfPCell c = lined();
        if (fill != null) {
            c.setBackgroundColor(fill);
        }
        Paragraph p = para(s, f, 9, INK);
        c.addElement(alignRight ? right(p) : p);
        return c;
    }

    /** One numbered clause per line. */
    private void clauses(Document doc, OfferLetter o) {
        List<String> lines = o.getClauses() == null ? List.of() : o.getClauses().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        int n = 0;
        for (String line : lines) {
            Paragraph p = para(++n + ".  " + line, regular, 9, INK);
            p.setIndentationLeft(16);
            p.setFirstLineIndent(-16);
            p.setSpacingAfter(3);
            doc.add(p);
        }
    }

    /** Kept on one page, so a signature is never apart from whose it is. */
    private PdfPTable signatures(OfferLetter o, String company) {
        PdfPTable t = table(new float[]{50, 50});
        t.setKeepTogether(true);
        PdfPCell issuer = cell();
        issuer.setPaddingRight(16);
        issuer.addElement(para("For " + company, bold, 9.5f, INK));
        issuer.addElement(spaced(para(" ", regular, 22, INK), 0, 0));
        issuer.addElement(para(text(o.getIssuedByName(), BLANK), bold, 9.5f, INK));
        issuer.addElement(para(text(o.getIssuedByDesignation(), BLANK), regular, 9, MUTED));
        t.addCell(issuer);
        PdfPCell candidate = cell();
        candidate.addElement(para("Accepted by", bold, 9.5f, INK));
        candidate.addElement(spaced(para(" ", regular, 22, INK), 0, 0));
        candidate.addElement(para(o.getCandidateName(), bold, 9.5f, INK));
        candidate.addElement(para("Signature and date", regular, 9, MUTED));
        t.addCell(candidate);
        return t;
    }

    private Paragraph heading(String s) {
        Paragraph p = para(s, bold, 10.5f, BLUE);
        p.setSpacingBefore(12);
        p.setSpacingAfter(5);
        p.setKeepTogether(true);
        return p;
    }

    private Paragraph body(String s) {
        Paragraph p = para(s, regular, 9.5f, INK);
        p.setAlignment(Element.ALIGN_LEFT);
        return p;
    }

    private static Paragraph right(Paragraph p) {
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private static String date(LocalDate d) {
        return d == null ? BLANK : d.format(DATE);
    }

    private static String text(String s, String otherwise) {
        return s == null ? otherwise : s;
    }

    private static PdfPCell cell() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    private static PdfPCell lined() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(LINE);
        c.setPadding(5);
        c.setPaddingTop(2);
        return c;
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        return t;
    }

    private static Paragraph para(String s, BaseFont f, float size, Color colour) {
        Paragraph p = new Paragraph(s, new Font(f, size, Font.NORMAL, colour));
        p.setLeading(size * 1.4f);
        return p;
    }

    private static <E extends Element> E spaced(E e, float before, float after) {
        if (e instanceof Paragraph p) {
            p.setSpacingBefore(before);
            p.setSpacingAfter(after);
        } else if (e instanceof PdfPTable t) {
            t.setSpacingBefore(before);
            t.setSpacingAfter(after);
        }
        return e;
    }

    private static BaseFont font(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return BaseFont.createFont(path.substring(path.lastIndexOf('/') + 1), BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
                    true, bytes, null);
        }
    }

    /** The reference and page number at the foot of each page; DRAFT across a draft. */
    private final class Pages extends PdfPageEventHelper {
        private final String reference;
        private final boolean draft;

        Pages(String reference, boolean draft) {
            this.reference = reference;
            this.draft = draft;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte over = writer.getDirectContent();
            Font small = new Font(regular, 7.5f, Font.NORMAL, MUTED);
            float y = document.bottom() - 22;
            ColumnText.showTextAligned(over, Element.ALIGN_LEFT, new Phrase(reference, small), document.left(), y, 0);
            ColumnText.showTextAligned(over, Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), small), document.right(), y, 0);
            if (draft) {
                over.saveState();
                PdfGState faint = new PdfGState();
                faint.setFillOpacity(0.10f);
                over.setGState(faint);
                ColumnText.showTextAligned(over, Element.ALIGN_CENTER, new Phrase("DRAFT", new Font(bold, 110, Font.NORMAL, STAMP)),
                        (document.left() + document.right()) / 2, (document.top() + document.bottom()) / 2 - 30, 45);
                over.restoreState();
            }
        }
    }
}
