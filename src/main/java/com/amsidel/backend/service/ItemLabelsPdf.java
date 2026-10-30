/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.ProductItem;
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
import java.util.List;

/**
 * A sheet of item QR labels on A4: four across, each with the QR code, the item code, its name and (if asked) the
 * showroom price. Each item is repeated as many times as copies asked; cut along the light lines.
 */
@Component
public class ItemLabelsPdf {

    private static final Color MUTED = new Color(0x55, 0x5f, 0x6b);
    private static final Color LINE = new Color(0xd5, 0xdb, 0xe3);
    private static final int ACROSS = 4;

    private final BaseFont regular;
    private final BaseFont bold;

    public ItemLabelsPdf() throws IOException {
        regular = font("payslip/fonts/DejaVuSans.ttf");
        bold = font("payslip/fonts/DejaVuSans-Bold.ttf");
    }

    /** One label to print: the item and how many copies. */
    public record Copies(ProductItem item, int copies) {
    }

    public byte[] render(List<Copies> labels, boolean showPrice) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 18, 18, 18, 18);
        PdfWriter.getInstance(doc, out);
        doc.addTitle("Item QR labels");
        doc.open();
        PdfPTable t = new PdfPTable(ACROSS);
        t.setWidthPercentage(100);
        int n = 0;
        for (Copies c : labels) {
            String payload = ItemQr.payload(c.item());
            byte[] png = ItemQr.png(payload, 360);
            for (int k = 0; k < c.copies(); k++) {
                t.addCell(label(c.item(), png, showPrice));
                n++;
            }
        }
        // fill the last row
        while (n % ACROSS != 0) {
            PdfPCell empty = new PdfPCell();
            empty.setBorder(Rectangle.NO_BORDER);
            t.addCell(empty);
            n++;
        }
        doc.add(t);
        doc.close();
        return out.toByteArray();
    }

    private PdfPCell label(ProductItem i, byte[] png, boolean showPrice) {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(LINE);
        c.setBorderWidth(0.5f);
        c.setPadding(6);
        c.setFixedHeight(190);
        try {
            Image img = Image.getInstance(png);
            img.scaleToFit(118, 118);
            img.setAlignment(Element.ALIGN_CENTER);
            c.addElement(img);
        } catch (IOException e) {
            throw new IllegalStateException("The QR code could not be placed", e);
        }
        c.addElement(center(i.getCode(), bold, 9, Color.BLACK));
        c.addElement(center(i.getName(), regular, 7.5f, Color.BLACK));
        if (showPrice) {
            c.addElement(center(MoneyText.format(i.getShowroomPrice(), i.getBrand().getCurrency()) + " / " + i.getUnit(), bold, 8, Color.BLACK));
        }
        c.addElement(center(i.getBrand().getName(), regular, 6, MUTED));
        return c;
    }

    private static Paragraph center(String s, BaseFont f, float size, Color colour) {
        Paragraph p = new Paragraph(s, new Font(f, size, Font.NORMAL, colour));
        p.setAlignment(Element.ALIGN_CENTER);
        p.setLeading(size * 1.25f);
        return p;
    }

    private static BaseFont font(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return BaseFont.createFont(path.substring(path.lastIndexOf('/') + 1), BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true,
                    in.readAllBytes(), null);
        }
    }
}
