package com.amsidel.backend.service;

import com.amsidel.backend.dto.PayslipDtos.LineView;
import com.amsidel.backend.dto.PayslipDtos.PayslipView;
import com.amsidel.backend.entity.PayComponentKind;
import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * A payslip as a PDF, in the portal's own salary-statement design (blue): a band with the brand's logo, name, address
 * and GSTIN; the employee's details as a grid; earnings, deductions and reimbursements with the full-month rate,
 * this month and year to date; a summary with the net pay and the amount in words.
 * DejaVu Sans (in resources/payslip/fonts, free licence) is used because it has the rupee sign.
 */
@Component
public class PayslipPdf {

    private static final Color INK = new Color(0x1f, 0x26, 0x30);
    private static final Color MUTED = new Color(0x5b, 0x68, 0x78);
    private static final Color LINE = new Color(0xdc, 0xe7, 0xf2);
    private static final Color BLUE = new Color(0x1d, 0x5f, 0xa3);
    private static final Color BLUE_MID = new Color(0x2f, 0x80, 0xd6);
    private static final Color BLUE_SOFT = new Color(0xee, 0xf5, 0xfd);
    private static final Color BLUE_TEXT = new Color(0xd6, 0xe8, 0xfb);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final BaseFont regular;
    private final BaseFont bold;
    private final byte[] defaultLogo;

    public PayslipPdf() throws IOException {
        regular = font("payslip/fonts/DejaVuSans.ttf");
        bold = font("payslip/fonts/DejaVuSans-Bold.ttf");
        try (InputStream in = new ClassPathResource("payslip/logo.png").getInputStream()) {
            defaultLogo = in.readAllBytes();
        }
    }

    /** brandLogo: the brand's own logo, or null for the group logo. */
    public byte[] render(PayslipView p, byte[] brandLogo) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 32, 32, 30, 30);
        PdfWriter.getInstance(doc, out);
        doc.addTitle("Salary statement " + p.periodLabel() + " - " + p.employee().name());
        doc.addAuthor(p.company().name());
        doc.open();

        doc.add(band(p, brandLogo));
        doc.add(spaced(details(p), 12, 0));
        doc.add(spaced(lines("Earnings", of(p, PayComponentKind.EARNING), "Gross earnings", p.money().gross()), 14, 0));
        doc.add(spaced(lines("Deductions", of(p, PayComponentKind.DEDUCTION), "Total deductions", p.money().deductions()), 10, 0));
        List<LineView> reimbursements = of(p, PayComponentKind.REIMBURSEMENT);
        if (!reimbursements.isEmpty()) {
            doc.add(spaced(lines("Reimbursements", reimbursements, "Total reimbursements", p.money().reimbursements()), 10, 0));
        }
        doc.add(spaced(summary(p), 14, 0));
        doc.add(spaced(para("Net pay = gross earnings - deductions + reimbursements. System-generated statement from the "
                + "Amsidel Company Portal; no signature is needed.", regular, 7, MUTED), 12, 0));
        doc.close();
        return out.toByteArray();
    }

    /** Blue band: logo on a white tile, brand name, address and GSTIN; "SALARY STATEMENT" and the month on the right. */
    private PdfPTable band(PayslipView p, byte[] brandLogo) {
        PdfPTable t = table(new float[]{17, 53, 30});
        PdfPCell logoCell = cell(Rectangle.BOX);
        logoCell.setBackgroundColor(Color.WHITE);
        logoCell.setBorderWidth(6);
        logoCell.setBorderColor(BLUE);
        logoCell.setPadding(4);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        try {
            Image img = Image.getInstance(brandLogo != null ? brandLogo : defaultLogo);
            img.scaleToFit(70, 52);
            logoCell.setImage(img);
        } catch (IOException e) {
            throw new IllegalStateException("The logo could not be read", e);
        }
        t.addCell(logoCell);

        PdfPCell name = cell(Rectangle.NO_BORDER);
        name.setBackgroundColor(BLUE);
        name.setPadding(12);
        name.addElement(para(p.company().name(), bold, 14, Color.WHITE));
        if (p.company().address() != null) {
            name.addElement(para(p.company().address(), regular, 7.5f, BLUE_TEXT));
        }
        if (p.company().gstNumber() != null) {
            name.addElement(para("GSTIN " + p.company().gstNumber(), regular, 7.5f, BLUE_TEXT));
        }
        String contact = String.join("  ·  ", java.util.stream.Stream.of(p.company().phone(), p.company().email())
                .filter(x -> x != null && !x.isBlank()).toList());
        if (!contact.isEmpty()) {
            name.addElement(para(contact, regular, 7.5f, BLUE_TEXT));
        }
        t.addCell(name);

        PdfPCell right = cell(Rectangle.NO_BORDER);
        right.setBackgroundColor(BLUE);
        right.setPadding(12);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Paragraph title = para("SALARY STATEMENT", bold, 9, BLUE_TEXT);
        title.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(title);
        Paragraph month = para(p.periodLabel(), bold, 14, Color.WHITE);
        month.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(month);
        t.addCell(right);
        return t;
    }

    /** The employee's details as a 3 x 3 grid of small boxes. */
    private PdfPTable details(PayslipView p) {
        PdfPTable t = table(new float[]{1, 1, 1});
        box(t, "Employee", p.employee().name());
        box(t, "Employee no.", p.employee().empNumber());
        box(t, "Designation", or(p.designationName()));
        box(t, "Date of joining", p.employee().joinedDate() == null ? "-" : DATE.format(p.employee().joinedDate()));
        box(t, "PAN", or(p.employee().pan()));
        box(t, "Bank account", or(p.employee().bankAccount())
                + (p.employee().ifsc() == null ? "" : "\nIFSC " + p.employee().ifsc()));
        box(t, "Pay date", DATE.format(p.payDate()));
        box(t, "Working days", String.valueOf(p.workingDays()));
        box(t, "Paid days", p.paidDays() + " of " + p.workingDays());
        box(t, "Loss of pay days", String.valueOf(p.lopDays()));
        box(t, "Credited on", p.paidOn() == null ? "Not yet" : DATE.format(p.paidOn()));
        box(t, "Paid by", p.paidOn() == null ? "-" : mode(p.paymentMode())
                + (p.paymentRef() == null ? "" : "\nTxn ID " + p.paymentRef()));
        return t;
    }

    private void box(PdfPTable t, String label, String value) {
        PdfPCell c = cell(Rectangle.BOX);
        c.setBorderColor(Color.WHITE);
        c.setBorderWidth(2);
        c.setBackgroundColor(BLUE_SOFT);
        c.setPadding(7);
        c.addElement(para(label.toUpperCase(Locale.ROOT), regular, 6.5f, MUTED));
        c.addElement(para(value, bold, 9, INK));
        t.addCell(c);
    }

    /** A section of lines: description, full-month rate, this month, year to date; total underneath. */
    private PdfPTable lines(String title, List<LineView> lines, String totalLabel, BigDecimal total) {
        PdfPTable t = table(new float[]{40, 20, 20, 20});
        head(t, title, Element.ALIGN_LEFT);
        head(t, "Rate", Element.ALIGN_RIGHT);
        head(t, "This month", Element.ALIGN_RIGHT);
        head(t, "Year to date", Element.ALIGN_RIGHT);
        if (lines.isEmpty()) {
            PdfPCell none = text("None", regular, Element.ALIGN_LEFT, MUTED);
            none.setColspan(4);
            t.addCell(none);
        }
        for (LineView l : lines) {
            t.addCell(rowCell(l.name(), Element.ALIGN_LEFT, INK));
            t.addCell(rowCell(rupees(l.fullAmount()), Element.ALIGN_RIGHT, MUTED));
            t.addCell(rowCell(rupees(l.amount()), Element.ALIGN_RIGHT, INK));
            t.addCell(rowCell(rupees(l.ytd()), Element.ALIGN_RIGHT, MUTED));
        }
        PdfPCell label = text(totalLabel, bold, Element.ALIGN_LEFT, BLUE);
        label.setColspan(2);
        t.addCell(label);
        t.addCell(text(rupees(total), bold, Element.ALIGN_RIGHT, BLUE));
        t.addCell(text("", regular, Element.ALIGN_RIGHT, INK));
        return t;
    }

    private void head(PdfPTable t, String s, int align) {
        PdfPCell c = text(s, bold, align, BLUE);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderWidthBottom(1.5f);
        c.setBorderColorBottom(BLUE_MID);
        c.setPaddingBottom(5);
        t.addCell(c);
    }

    private PdfPCell rowCell(String s, int align, Color colour) {
        PdfPCell c = text(s, regular, align, colour);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColorBottom(LINE);
        return c;
    }

    /** Amount in words on the left; on the right gross, less deductions, add reimbursements and the net pay in blue. */
    private PdfPTable summary(PayslipView p) {
        PdfPTable outer = table(new float[]{45, 55});
        PdfPCell words = cell(Rectangle.NO_BORDER);
        words.setPaddingRight(14);
        words.setVerticalAlignment(Element.ALIGN_BOTTOM);
        words.addElement(para("AMOUNT IN WORDS", regular, 6.5f, MUTED));
        words.addElement(para(p.netInWords(), bold, 8.5f, INK));
        outer.addCell(words);

        PdfPTable s = table(new float[]{55, 45});
        sumRow(s, "Gross earnings", rupees(p.money().gross()));
        sumRow(s, "Less: deductions", "- " + rupees(p.money().deductions()));
        sumRow(s, "Add: reimbursements", "+ " + rupees(p.money().reimbursements()));
        PdfPCell netLabel = cell(Rectangle.NO_BORDER);
        netLabel.setBackgroundColor(BLUE);
        netLabel.setPadding(9);
        netLabel.setPhrase(new Phrase("NET PAY", new Font(bold, 10, Font.NORMAL, Color.WHITE)));
        s.addCell(netLabel);
        PdfPCell netValue = cell(Rectangle.NO_BORDER);
        netValue.setBackgroundColor(BLUE);
        netValue.setPadding(9);
        netValue.setHorizontalAlignment(Element.ALIGN_RIGHT);
        netValue.setPhrase(new Phrase(rupees(p.money().net()), new Font(bold, 13, Font.NORMAL, Color.WHITE)));
        s.addCell(netValue);
        PdfPCell right = cell(Rectangle.BOX);
        right.setBorderColor(LINE);
        right.setPadding(0);
        right.addElement(s);
        outer.addCell(right);
        return outer;
    }

    private void sumRow(PdfPTable t, String label, String value) {
        PdfPCell l = text(label, regular, Element.ALIGN_LEFT, MUTED);
        l.setPaddingLeft(9);
        t.addCell(l);
        PdfPCell v = text(value, regular, Element.ALIGN_RIGHT, INK);
        v.setPaddingRight(9);
        t.addCell(v);
    }

    private static List<LineView> of(PayslipView p, PayComponentKind kind) {
        return p.lines().stream().filter(l -> l.kind() == kind).toList();
    }

    private PdfPCell text(String s, BaseFont f, int align, Color colour) {
        PdfPCell c = new PdfPCell(new Phrase(s, new Font(f, 8.5f, Font.NORMAL, colour)));
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        c.setPaddingTop(4);
        c.setPaddingBottom(5);
        c.setPaddingLeft(4);
        c.setPaddingRight(4);
        return c;
    }

    private static PdfPCell cell(int border) {
        PdfPCell c = new PdfPCell();
        c.setBorder(border);
        return c;
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        return t;
    }

    private static Paragraph para(String s, BaseFont f, float size, Color colour) {
        Paragraph p = new Paragraph(s, new Font(f, size, Font.NORMAL, colour));
        p.setLeading(size * 1.35f);
        return p;
    }

    private static Element spaced(Element e, float before, float after) {
        if (e instanceof Paragraph p) {
            p.setSpacingBefore(before);
            p.setSpacingAfter(after);
        } else if (e instanceof PdfPTable t) {
            t.setSpacingBefore(before);
            t.setSpacingAfter(after);
        }
        return e;
    }

    private static String mode(com.amsidel.backend.entity.PaymentMode m) {
        if (m == null) {
            return "-";
        }
        return switch (m) {
            case BANK -> "Bank transfer";
            case UPI -> "UPI";
            case CASH -> "Cash";
        };
    }

    private static String or(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** 214945 -> "₹2,14,945.00" (Indian grouping: the last three digits, then twos). */
    static String rupees(BigDecimal v) {
        BigDecimal a = (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
        String digits = a.abs().toPlainString();
        String whole = digits.substring(0, digits.indexOf('.'));
        String paise = digits.substring(digits.indexOf('.'));
        StringBuilder s = new StringBuilder();
        int n = whole.length();
        if (n > 3) {
            String head = whole.substring(0, n - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) {
                    s.append(',');
                }
                s.append(head.charAt(i));
            }
            s.append(',').append(whole.substring(n - 3));
        } else {
            s.append(whole);
        }
        return (a.signum() < 0 ? "-₹" : "₹") + s + paise;
    }

    private static BaseFont font(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return BaseFont.createFont(path.substring(path.lastIndexOf('/') + 1), BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
                    true, bytes, null);
        }
    }
}
