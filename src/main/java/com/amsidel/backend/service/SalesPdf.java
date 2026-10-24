package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.*;
import com.amsidel.backend.entity.SalesDocKind;
import com.amsidel.backend.entity.SalesDocStatus;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A quotation or tax invoice as a PDF, in the portal's blue: the brand's band (logo, name, address, GSTIN,
 * contact), who it is for and the bill's details, the lines, the totals with CGST + SGST (or IGST), the amount in
 * words, the brand's bank and UPI details for paying (invoices only), remarks and terms. A draft or cancelled bill says so across
 * the page.
 */
@Component
public class SalesPdf {

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

    public SalesPdf() throws IOException {
        regular = font("payslip/fonts/DejaVuSans.ttf");
        bold = font("payslip/fonts/DejaVuSans-Bold.ttf");
        try (InputStream in = new ClassPathResource("payslip/logo.png").getInputStream()) {
            defaultLogo = in.readAllBytes();
        }
    }

    /** brandLogo / upiQr: the brand's own (null = the group logo / a QR made from the brand's UPI ID). */
    public byte[] render(SalesView d, byte[] brandLogo, byte[] upiQr) {
        boolean invoice = d.kind() == SalesDocKind.INVOICE;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 32, 32, 30, 36);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        String title = invoice ? "TAX INVOICE" : "QUOTATION";
        doc.addTitle(title + " " + number(d));
        doc.addAuthor(d.company().name());
        doc.open();
        if (d.status() == SalesDocStatus.DRAFT && invoice || d.status() == SalesDocStatus.CANCELLED) {
            stamp(writer, d.status() == SalesDocStatus.CANCELLED ? "CANCELLED" : "DRAFT");
        }
        doc.add(band(d, title, brandLogo));
        doc.add(spaced(parties(d), 12, 0));
        doc.add(spaced(lines(d), 12, 0));
        doc.add(spaced(summary(d), 10, 0));
        // a quotation is only an offer: the bank and UPI details are on the invoice
        PdfPTable pay = invoice ? payment(d, upiQr) : null;
        if (pay != null) {
            doc.add(spaced(pay, 12, 0));
        }
        if (d.remarks() != null) {
            doc.add(spaced(para("REMARKS", regular, 6.5f, MUTED), 12, 0));
            doc.add(para(d.remarks(), regular, 8, INK));
        }
        if (d.terms() != null) {
            doc.add(spaced(para("TERMS & CONDITIONS", regular, 6.5f, MUTED), 10, 0));
            doc.add(para(d.terms(), regular, 7.5f, INK));
        }
        doc.add(spaced(signature(d), 22, 0));
        doc.add(spaced(para("System-generated " + (invoice ? "invoice" : "quotation") + " from the " + d.company().name()
                + " company portal.", regular, 7, MUTED), 10, 0));
        doc.close();
        return out.toByteArray();
    }

    private static String number(SalesView d) {
        return d.number() == null ? "(draft)" : d.number();
    }

    private PdfPTable band(SalesView d, String title, byte[] brandLogo) {
        PdfPTable t = table(new float[]{17, 51, 32});
        PdfPCell logoCell = cell(Rectangle.BOX);
        logoCell.setBackgroundColor(Color.WHITE);
        logoCell.setBorderWidth(6);
        logoCell.setBorderColor(BLUE);
        logoCell.setPadding(4);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
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
        Company c = d.company();
        name.addElement(para(c.name(), bold, 14, Color.WHITE));
        if (c.address() != null) {
            name.addElement(para(c.address(), regular, 7.5f, BLUE_TEXT));
        }
        if (c.gstNumber() != null) {
            name.addElement(para("GSTIN " + c.gstNumber(), regular, 7.5f, BLUE_TEXT));
        }
        String contact = join("  ·  ", c.phone(), c.email());
        if (!contact.isEmpty()) {
            name.addElement(para(contact, regular, 7.5f, BLUE_TEXT));
        }
        t.addCell(name);
        PdfPCell right = cell(Rectangle.NO_BORDER);
        right.setBackgroundColor(BLUE);
        right.setPadding(12);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);
        right.addElement(rightPara(title, bold, 10, BLUE_TEXT));
        right.addElement(rightPara(number(d), bold, 12, Color.WHITE));
        right.addElement(rightPara(DATE.format(d.docDate()), regular, 8, BLUE_TEXT));
        t.addCell(right);
        return t;
    }

    private PdfPTable parties(SalesView d) {
        PdfPTable t = table(new float[]{55, 45});
        PartyRef c = d.customer();
        PdfPCell to = softBox();
        to.addElement(para(d.kind() == SalesDocKind.INVOICE ? "BILL TO" : "QUOTATION FOR", regular, 6.5f, MUTED));
        to.addElement(para(c.name(), bold, 10, INK));
        to.addElement(para(join("  ·  ", c.code(), c.phone(), c.email()), regular, 7.5f, MUTED));
        if (c.address() != null) {
            to.addElement(para(c.address(), regular, 8, INK));
        }
        if (c.gstNumber() != null) {
            to.addElement(para("GSTIN " + c.gstNumber(), bold, 8, INK));
        }
        t.addCell(to);
        PdfPCell info = softBox();
        info.addElement(kv(d.kind() == SalesDocKind.INVOICE ? "Invoice no." : "Quotation no.", number(d)));
        info.addElement(kv("Date", DATE.format(d.docDate())));
        if (d.validUntil() != null) {
            info.addElement(kv("Valid until", DATE.format(d.validUntil())));
        }
        if (d.quotationNumber() != null) {
            info.addElement(kv("Quotation", d.quotationNumber()));
        }
        info.addElement(kv("Supply", d.totals().interState() ? "Inter-state (IGST)" : "Within the state (CGST + SGST)"));
        if (d.createdByName() != null) {
            info.addElement(kv("Prepared by", d.createdByName() + (d.createdByDesignation() == null ? "" : ", " + d.createdByDesignation())));
        }
        if (d.issuedByName() != null && !d.issuedByName().equals(d.createdByName())) {
            info.addElement(kv("Issued by", d.issuedByName()));
        }
        t.addCell(info);
        return t;
    }

    private Paragraph kv(String k, String v) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(k + ":  ", new Font(regular, 8, Font.NORMAL, MUTED)));
        p.add(new Chunk(v, new Font(bold, 8.5f, Font.NORMAL, INK)));
        p.setLeading(13);
        return p;
    }

    private PdfPTable lines(SalesView d) {
        PdfPTable t = table(new float[]{5, 41, 10, 9, 8, 13, 14});
        head(t, "#", Element.ALIGN_LEFT);
        head(t, "Item", Element.ALIGN_LEFT);
        head(t, "HSN", Element.ALIGN_LEFT);
        head(t, "Qty", Element.ALIGN_RIGHT);
        head(t, "Unit", Element.ALIGN_LEFT);
        head(t, "Rate", Element.ALIGN_RIGHT);
        head(t, "Amount", Element.ALIGN_RIGHT);
        int n = 1;
        for (SalesLineView l : d.lines()) {
            t.addCell(row(String.valueOf(n++), Element.ALIGN_LEFT, MUTED));
            PdfPCell item = row(null, Element.ALIGN_LEFT, INK);
            item.addElement(para(l.name(), bold, 8.5f, INK));
            String sub = join(" · ", l.itemCode(), l.typeName(), l.size() == null ? null : "Size " + l.size());
            if (!sub.isEmpty()) {
                item.addElement(para(sub, regular, 7, MUTED));
            }
            if (l.description() != null) {
                item.addElement(para(l.description(), regular, 7.5f, INK));
            }
            t.addCell(item);
            t.addCell(row(l.hsnCode() == null ? "" : l.hsnCode(), Element.ALIGN_LEFT, MUTED));
            t.addCell(row(StockLedger.plain(l.quantity()), Element.ALIGN_RIGHT, INK));
            t.addCell(row(l.unit(), Element.ALIGN_LEFT, MUTED));
            t.addCell(row(PayslipPdf.rupees(l.rate()), Element.ALIGN_RIGHT, INK));
            t.addCell(row(PayslipPdf.rupees(l.amount()), Element.ALIGN_RIGHT, INK));
        }
        return t;
    }

    private PdfPTable summary(SalesView d) {
        Totals m = d.totals();
        PdfPTable outer = table(new float[]{52, 48});
        PdfPCell left = cell(Rectangle.NO_BORDER);
        left.setPaddingRight(14);
        left.addElement(para("AMOUNT IN WORDS", regular, 6.5f, MUTED));
        left.addElement(para(RupeesInWords.of(m.total()), bold, 8.5f, INK));
        outer.addCell(left);

        PdfPTable s = table(new float[]{58, 42});
        sumRow(s, "Subtotal", PayslipPdf.rupees(m.subtotal()));
        if (m.discount().signum() > 0) {
            sumRow(s, "Less: discount", "- " + PayslipPdf.rupees(m.discount()));
        }
        if (m.miscAmount().signum() > 0) {
            sumRow(s, "Add: " + (m.miscLabel() == null ? "other charges" : m.miscLabel()), "+ " + PayslipPdf.rupees(m.miscAmount()));
        }
        sumRow(s, "Taxable value", PayslipPdf.rupees(m.taxable()));
        String rate = m.gstRate().stripTrailingZeros().toPlainString();
        if (m.interState()) {
            sumRow(s, "IGST " + rate + "%", PayslipPdf.rupees(m.igst()));
        } else if (m.gstRate().signum() > 0) {
            String half = m.gstRate().divide(BigDecimal.valueOf(2)).stripTrailingZeros().toPlainString();
            sumRow(s, "CGST " + half + "%", PayslipPdf.rupees(m.cgst()));
            sumRow(s, "SGST " + half + "%", PayslipPdf.rupees(m.sgst()));
        }
        if (m.roundOff().signum() != 0) {
            sumRow(s, "Round off", (m.roundOff().signum() > 0 ? "+ " : "- ") + PayslipPdf.rupees(m.roundOff().abs()));
        }
        PdfPCell totalLabel = cell(Rectangle.NO_BORDER);
        totalLabel.setBackgroundColor(BLUE);
        totalLabel.setPadding(9);
        totalLabel.setPhrase(new Phrase(d.kind() == SalesDocKind.INVOICE ? "TOTAL" : "QUOTED TOTAL", new Font(bold, 10, Font.NORMAL, Color.WHITE)));
        s.addCell(totalLabel);
        PdfPCell totalValue = cell(Rectangle.NO_BORDER);
        totalValue.setBackgroundColor(BLUE);
        totalValue.setPadding(9);
        totalValue.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totalValue.setPhrase(new Phrase(PayslipPdf.rupees(m.total()), new Font(bold, 12, Font.NORMAL, Color.WHITE)));
        s.addCell(totalValue);
        if (d.kind() == SalesDocKind.INVOICE && m.paid().signum() > 0) {
            sumRow(s, "Paid", PayslipPdf.rupees(m.paid()));
            if (m.writtenOff().signum() > 0) {
                sumRow(s, "Discount allowed", PayslipPdf.rupees(m.writtenOff()));
            }
            sumRow(s, "Balance due", PayslipPdf.rupees(m.due()));
        }
        PdfPCell right = cell(Rectangle.BOX);
        right.setBorderColor(LINE);
        right.setPadding(0);
        right.addElement(s);
        outer.addCell(right);
        return outer;
    }

    /**
     * How to pay: the brand's bank account, IFSC and UPI ID, and a UPI QR code: the brand's uploaded one, or one made
     * from its UPI ID with the amount still to pay and the bill number filled in. Nothing when the brand has neither;
     * no QR once an invoice is fully paid or cancelled.
     */
    private PdfPTable payment(SalesView d, byte[] upiQr) {
        Company c = d.company();
        boolean invoice = d.kind() == SalesDocKind.INVOICE;
        if (c.bankAccount() == null && c.upi() == null && upiQr == null) {
            return null;
        }
        BigDecimal toPay = invoice ? d.totals().due() : d.totals().total();
        boolean settled = invoice && (d.status() == SalesDocStatus.CANCELLED || toPay.signum() <= 0);
        byte[] qr = settled ? null : upiQr != null ? upiQr
                : c.upi() == null ? null
                : UpiQr.png(UpiQr.link(c.upi(), c.name(), invoice ? toPay : null, d.number() == null ? null : d.number()), 360);

        PdfPTable t = table(qr == null ? new float[]{1} : new float[]{70, 30});
        PdfPCell details = softBox();
        details.addElement(para(settled ? "PAID WITH THANKS" : "HOW TO PAY", bold, 7, BLUE));
        details.addElement(para(c.name(), bold, 9.5f, INK));
        if (c.bankAccount() != null) {
            details.addElement(kv("Bank account", c.bankAccount()));
        }
        if (c.ifsc() != null) {
            details.addElement(kv("IFSC", c.ifsc()));
        }
        if (c.upi() != null) {
            details.addElement(kv("UPI ID", c.upi()));
        }
        if (!settled) {
            details.addElement(kv(invoice ? "Amount to pay" : "Quoted total", PayslipPdf.rupees(toPay)));
            if (d.number() != null) {
                details.addElement(para("Please mention " + d.number() + " with the payment.", regular, 7.5f, MUTED));
            }
        }
        t.addCell(details);
        if (qr != null) {
            PdfPCell q = softBox();
            q.setHorizontalAlignment(Element.ALIGN_CENTER);
            try {
                Image img = Image.getInstance(qr);
                img.scaleToFit(96, 96);
                img.setAlignment(Element.ALIGN_CENTER);
                q.addElement(img);
            } catch (IOException e) {
                throw new IllegalStateException("The QR code could not be read", e);
            }
            Paragraph cap = para(upiQr != null || !invoice ? "Scan with any UPI app" : "Scan to pay " + PayslipPdf.rupees(toPay),
                    bold, 7.5f, INK);
            cap.setAlignment(Element.ALIGN_CENTER);
            q.addElement(cap);
            Paragraph apps = para("GPay · PhonePe · Paytm · BHIM", regular, 6.5f, MUTED);
            apps.setAlignment(Element.ALIGN_CENTER);
            q.addElement(apps);
            t.addCell(q);
        }
        return t;
    }

    private PdfPTable signature(SalesView d) {
        PdfPTable t = table(new float[]{60, 40});
        t.addCell(cell(Rectangle.NO_BORDER));
        PdfPCell c = cell(Rectangle.TOP);
        c.setBorderColor(LINE);
        c.setPaddingTop(6);
        c.addElement(rightPara("For " + d.company().name(), bold, 8.5f, INK));
        c.addElement(rightPara("Authorised signatory", regular, 7.5f, MUTED));
        t.addCell(c);
        return t;
    }

    private void stamp(PdfWriter writer, String text) {
        writer.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter w, Document document) {
                PdfContentByte under = w.getDirectContentUnder();
                under.saveState();
                PdfGState gs = new PdfGState();
                gs.setFillOpacity(0.08f);
                under.setGState(gs);
                under.beginText();
                under.setFontAndSize(bold, 90);
                under.setColorFill(text.equals("CANCELLED") ? new Color(0xb3, 0x26, 0x1e) : BLUE);
                under.showTextAligned(Element.ALIGN_CENTER, text, 298, 420, 35);
                under.endText();
                under.restoreState();
            }
        });
    }

    private PdfPCell softBox() {
        PdfPCell c = cell(Rectangle.BOX);
        c.setBorderColor(Color.WHITE);
        c.setBorderWidth(2);
        c.setBackgroundColor(BLUE_SOFT);
        c.setPadding(9);
        return c;
    }

    private void head(PdfPTable t, String s, int align) {
        PdfPCell c = text(s, bold, align, BLUE);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderWidthBottom(1.5f);
        c.setBorderColorBottom(BLUE_MID);
        c.setPaddingBottom(5);
        t.addCell(c);
    }

    private PdfPCell row(String s, int align, Color colour) {
        PdfPCell c = s == null ? cell(Rectangle.NO_BORDER) : text(s, regular, align, colour);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColorBottom(LINE);
        c.setPaddingTop(4);
        c.setPaddingBottom(6);
        return c;
    }

    private void sumRow(PdfPTable t, String label, String value) {
        PdfPCell l = text(label, regular, Element.ALIGN_LEFT, MUTED);
        l.setPaddingLeft(9);
        t.addCell(l);
        PdfPCell v = text(value, regular, Element.ALIGN_RIGHT, INK);
        v.setPaddingRight(9);
        t.addCell(v);
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

    private static Paragraph rightPara(String s, BaseFont f, float size, Color colour) {
        Paragraph p = para(s, f, size, colour);
        p.setAlignment(Element.ALIGN_RIGHT);
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

    private static String join(String sep, String... parts) {
        return String.join(sep, Stream.of(parts).filter(p -> p != null && !p.isBlank()).toList());
    }

    private static BaseFont font(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return BaseFont.createFont(path.substring(path.lastIndexOf('/') + 1), BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
                    true, bytes, null);
        }
    }
}
