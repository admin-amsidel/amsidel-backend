/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.TradeDtos.Company;
import com.amsidel.backend.dto.TradeDtos.PartyRef;
import com.amsidel.backend.dto.TradeDtos.SalesLineView;
import com.amsidel.backend.dto.TradeDtos.SalesView;
import com.amsidel.backend.dto.TradeDtos.Totals;
import com.amsidel.backend.entity.CurrencyCode;
import com.amsidel.backend.entity.SalesDocKind;
import com.amsidel.backend.entity.SalesDocStatus;
import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.DottedLineSeparator;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A quotation or invoice as a receipt on a roll 7, 12 or 15 cm wide (restaurant and counter printers): one narrow
 * column, black on white, and a page exactly as long as the bill, so the printer cuts it with no paper wasted.
 * Drawn twice: once on a very long page to measure it, then on a page of that length.
 */
@Component
public class ReceiptPdf {

    private static final Color INK = Color.BLACK;
    private static final Color MUTED = new Color(0x44, 0x44, 0x44);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    /** The longest page a PDF allows (200 inches); a longer bill goes on to a second page. */
    private static final float LONGEST = 14_400f;

    private final BaseFont regular;
    private final BaseFont bold;

    public ReceiptPdf() throws IOException {
        regular = font("payslip/fonts/DejaVuSans.ttf");
        bold = font("payslip/fonts/DejaVuSans-Bold.ttf");
    }

    private static BaseFont font(String path) throws IOException {
        try (java.io.InputStream in = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return BaseFont.createFont(path.substring(path.lastIndexOf('/') + 1), BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true,
                    in.readAllBytes(), null);
        }
    }

    /** brandLogo / upiQr: the brand's own (null = no logo / a QR made from the brand's UPI ID). */
    public byte[] render(SalesView d, byte[] brandLogo, byte[] upiQr, PaperSize size) {
        if (!size.isRoll()) {
            throw new IllegalArgumentException("A4 is drawn by SalesPdf");
        }
        float[] used = new float[1];
        draw(d, brandLogo, upiQr, size, LONGEST, used);
        // a little extra so rounding never pushes the last line on to a second page
        float height = Math.min(LONGEST, Math.max(used[0] + 16f, 144f));
        return draw(d, brandLogo, upiQr, size, height, used);
    }

    private byte[] draw(SalesView d, byte[] brandLogo, byte[] upiQr, PaperSize size, float pageHeight, float[] used) {
        Look k = new Look(size);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        float w = size.widthPoints();
        Document doc = new Document(new Rectangle(w, pageHeight), k.side, k.side, k.top, k.bottom);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        boolean invoice = d.kind() == SalesDocKind.INVOICE;
        String title = invoice ? "TAX INVOICE" : "QUOTATION";
        doc.addTitle(title + " " + number(d));
        doc.addAuthor(d.company().name());
        doc.open();
        if (d.status() == SalesDocStatus.CANCELLED || d.status() == SalesDocStatus.DRAFT && invoice) {
            doc.add(center(d.status() == SalesDocStatus.CANCELLED ? "*** CANCELLED ***" : "*** DRAFT: NOT A BILL ***", bold, k.body + 1));
        }
        header(doc, d, brandLogo, k);
        doc.add(rule());
        doc.add(center(title, bold, k.body + 1.5f));
        doc.add(pair(number(d), DATE.format(d.docDate()), k, true));
        if (!invoice && d.validUntil() != null) {
            doc.add(left("Valid until " + DATE.format(d.validUntil()), regular, k.small, MUTED));
        }
        PartyRef c = d.customer();
        doc.add(left((invoice ? "Bill to: " : "For: ") + c.name(), bold, k.body, INK));
        String contact = join(" · ", c.phone(), c.gstNumber() == null ? null : "GSTIN " + c.gstNumber());
        if (!contact.isEmpty()) {
            doc.add(left(contact, regular, k.small, MUTED));
        }
        doc.add(rule());
        doc.add(k.narrow ? itemsNarrow(d, k) : itemsWide(d, k));
        doc.add(rule());
        doc.add(totals(d, k));
        doc.add(spaced(left(MoneyText.words(d.totals().total(), currency(d)), regular, k.small, MUTED), 3));
        Element pay = invoice ? payment(d, upiQr, k, w) : null;
        if (pay != null) {
            doc.add(rule());
            doc.add(pay);
        }
        if (d.remarks() != null) {
            doc.add(rule());
            doc.add(left(d.remarks(), regular, k.small, INK));
        }
        if (d.terms() != null) {
            doc.add(spaced(left("Terms: " + d.terms(), regular, k.small - 0.5f, MUTED), 3));
        }
        doc.add(rule());
        doc.add(center(invoice ? "Thank you! Please visit again." : "Thank you for your interest.", bold, k.body));
        used[0] = pageHeight - writer.getVerticalPosition(true) + k.bottom;
        doc.close();
        return out.toByteArray();
    }

    // ---- parts ----

    private void header(Document doc, SalesView d, byte[] brandLogo, Look k) {
        Company c = d.company();
        if (brandLogo != null) {
            try {
                Image img = Image.getInstance(brandLogo);
                img.scaleToFit(k.logoWidth, k.logoHeight);
                img.setAlignment(Element.ALIGN_CENTER);
                doc.add(img);
            } catch (IOException | DocumentException e) {
                // a logo that cannot be read is left out; the bill still prints
            }
        }
        doc.add(center(c.name(), bold, k.body + 3));
        if (c.address() != null) {
            doc.add(center(c.address(), regular, k.small));
        }
        String contact = join(" · ", c.phone(), c.email());
        if (!contact.isEmpty()) {
            doc.add(center(contact, regular, k.small));
        }
        if (c.gstNumber() != null) {
            doc.add(center("GSTIN " + c.gstNumber(), regular, k.small));
        }
    }

    /** 7 cm: each item on its own line, then quantity x rate and the amount. */
    private PdfPTable itemsNarrow(SalesView d, Look k) {
        PdfPTable t = table(new float[]{62, 38});
        for (SalesLineView l : d.lines()) {
            PdfPCell name = cell(l.name() + (l.size() == null ? "" : " (" + l.size() + ")"), bold, k.body, Element.ALIGN_LEFT);
            name.setColspan(2);
            name.setPaddingTop(2);
            t.addCell(name);
            t.addCell(cell(StockLedger.plain(l.quantity()) + " " + nz(l.unit()) + " x " + money(d, l.rate()), regular, k.small, Element.ALIGN_LEFT));
            t.addCell(cell(money(d, l.amount()), regular, k.body, Element.ALIGN_RIGHT));
        }
        return t;
    }

    /** 12 and 15 cm: Item, Qty, Rate, Amount. */
    private PdfPTable itemsWide(SalesView d, Look k) {
        PdfPTable t = table(new float[]{46, 14, 19, 21});
        for (String[] h : new String[][]{{"Item", "L"}, {"Qty", "R"}, {"Rate", "R"}, {"Amount", "R"}}) {
            PdfPCell c = cell(h[0], bold, k.small, "L".equals(h[1]) ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(INK);
            c.setPaddingBottom(3);
            t.addCell(c);
        }
        for (SalesLineView l : d.lines()) {
            PdfPCell name = cell(l.name() + (l.size() == null ? "" : " (" + l.size() + ")"), regular, k.body, Element.ALIGN_LEFT);
            name.setPaddingTop(3);
            t.addCell(name);
            t.addCell(padded(cell(StockLedger.plain(l.quantity()) + (l.unit() == null ? "" : " " + l.unit()), regular, k.body, Element.ALIGN_RIGHT)));
            t.addCell(padded(cell(money(d, l.rate()), regular, k.body, Element.ALIGN_RIGHT)));
            t.addCell(padded(cell(money(d, l.amount()), regular, k.body, Element.ALIGN_RIGHT)));
        }
        return t;
    }

    private PdfPTable totals(SalesView d, Look k) {
        Totals m = d.totals();
        PdfPTable t = table(new float[]{58, 42});
        row(t, "Subtotal", money(d, m.subtotal()), k);
        if (m.discount().signum() > 0) {
            row(t, "Discount", "- " + money(d, m.discount()), k);
        }
        if (m.miscAmount().signum() > 0) {
            row(t, m.miscLabel() == null ? "Other charges" : m.miscLabel(), "+ " + money(d, m.miscAmount()), k);
        }
        String rate = m.gstRate().stripTrailingZeros().toPlainString();
        if (m.interState()) {
            row(t, "IGST " + rate + "%", money(d, m.igst()), k);
        } else if (m.gstRate().signum() > 0) {
            String half = m.gstRate().divide(BigDecimal.valueOf(2)).stripTrailingZeros().toPlainString();
            row(t, "CGST " + half + "%", money(d, m.cgst()), k);
            row(t, "SGST " + half + "%", money(d, m.sgst()), k);
        }
        if (m.roundOff().signum() != 0) {
            row(t, "Round off", (m.roundOff().signum() > 0 ? "+ " : "- ") + money(d, m.roundOff().abs()), k);
        }
        PdfPCell label = cell(d.kind() == SalesDocKind.INVOICE ? "TOTAL" : "QUOTED TOTAL", bold, k.body + 2, Element.ALIGN_LEFT);
        PdfPCell value = cell(money(d, m.total()), bold, k.body + 2, Element.ALIGN_RIGHT);
        for (PdfPCell c : new PdfPCell[]{label, value}) {
            c.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
            c.setBorderColor(INK);
            c.setPaddingTop(3);
            c.setPaddingBottom(4);
            t.addCell(c);
        }
        if (d.kind() == SalesDocKind.INVOICE && m.paid().signum() > 0) {
            row(t, "Paid", money(d, m.paid()), k);
            if (m.writtenOff().signum() > 0) {
                row(t, "Discount allowed", money(d, m.writtenOff()), k);
            }
            row(t, "Balance due", money(d, m.due()), k);
        }
        return t;
    }

    /** A UPI QR to pay what is still due (rupees only; none once paid or cancelled), with the brand's UPI ID. */
    private Element payment(SalesView d, byte[] upiQr, Look k, float pageWidth) {
        Company c = d.company();
        if (currency(d) != CurrencyCode.INR) {
            return null;
        }
        BigDecimal due = d.totals().due();
        if (d.status() == SalesDocStatus.CANCELLED || due.signum() <= 0 || (upiQr == null && c.upi() == null)) {
            return null;
        }
        byte[] qr = upiQr != null ? upiQr : UpiQr.png(UpiQr.link(c.upi(), c.name(), due, d.number()), 360);
        PdfPTable t = table(new float[]{1});
        try {
            Image img = Image.getInstance(qr);
            float side = Math.min(pageWidth * 0.55f, 130f);
            img.scaleToFit(side, side);
            PdfPCell q = new PdfPCell(img, false);
            q.setBorder(Rectangle.NO_BORDER);
            q.setHorizontalAlignment(Element.ALIGN_CENTER);
            q.setPaddingTop(4);
            t.addCell(q);
        } catch (IOException e) {
            return null;
        }
        t.addCell(cell(upiQr != null ? "Scan with any UPI app" : "Scan to pay " + money(d, due), bold, k.body, Element.ALIGN_CENTER));
        if (c.upi() != null) {
            t.addCell(cell("UPI " + c.upi(), regular, k.small, Element.ALIGN_CENTER));
        }
        return t;
    }

    // ---- helpers ----

    /** Sizes for a roll width: 7 cm is tight, 12 and 15 cm have room for columns. */
    private static final class Look {
        final boolean narrow;
        final float body;
        final float small;
        final float side;
        final float top = 8;
        final float bottom = 14;
        final float logoWidth;
        final float logoHeight;

        Look(PaperSize size) {
            narrow = size == PaperSize.MM70;
            body = narrow ? 7.5f : size == PaperSize.MM120 ? 8.5f : 9f;
            small = body - 1.2f;
            side = narrow ? 8 : 12;
            logoWidth = size.widthPoints() * 0.4f;
            logoHeight = narrow ? 34 : 44;
        }
    }

    private void row(PdfPTable t, String label, String value, Look k) {
        t.addCell(padded(cell(label, regular, k.body, Element.ALIGN_LEFT)));
        t.addCell(padded(cell(value, regular, k.body, Element.ALIGN_RIGHT)));
    }

    private static PdfPCell padded(PdfPCell c) {
        c.setPaddingTop(1.5f);
        c.setPaddingBottom(1.5f);
        return c;
    }

    private PdfPCell cell(String s, BaseFont f, float size, int align) {
        PdfPCell c = new PdfPCell(new Phrase(s, new Font(f, size, Font.NORMAL, INK)));
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        c.setPadding(0);
        c.setPaddingBottom(1);
        return c;
    }

    private Paragraph center(String s, BaseFont f, float size) {
        Paragraph p = new Paragraph(s, new Font(f, size, Font.NORMAL, INK));
        p.setAlignment(Element.ALIGN_CENTER);
        p.setLeading(size * 1.3f);
        return p;
    }

    private Paragraph left(String s, BaseFont f, float size, Color colour) {
        Paragraph p = new Paragraph(s, new Font(f, size, Font.NORMAL, colour));
        p.setLeading(size * 1.3f);
        return p;
    }

    /** Two values on one line: the first on the left, the second on the right. */
    private PdfPTable pair(String l, String r, Look k, boolean strong) {
        PdfPTable t = table(new float[]{55, 45});
        t.addCell(cell(l, strong ? bold : regular, k.body, Element.ALIGN_LEFT));
        t.addCell(cell(r, regular, k.body, Element.ALIGN_RIGHT));
        return t;
    }

    /** A dashed line across, with a little space around it. */
    private static Paragraph rule() {
        DottedLineSeparator dots = new DottedLineSeparator();
        dots.setGap(2.5f);
        dots.setLineWidth(0.6f);
        Paragraph p = new Paragraph(new Chunk(dots));
        p.setLeading(8);
        p.setSpacingAfter(3);
        return p;
    }

    private static Paragraph spaced(Paragraph p, float before) {
        p.setSpacingBefore(before);
        return p;
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSplitLate(false);
        return t;
    }

    private static CurrencyCode currency(SalesView d) {
        return CurrencyCode.of(d.company().currency());
    }

    private static String money(SalesView d, BigDecimal v) {
        return MoneyText.format(v, currency(d));
    }

    private static String number(SalesView d) {
        return d.number() == null ? "(draft)" : d.number();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String join(String sep, String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                b.append(b.isEmpty() ? "" : sep).append(p);
            }
        }
        return b.toString();
    }
}
