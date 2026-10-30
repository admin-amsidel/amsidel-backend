/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * UPI QR codes: reading the one in an uploaded picture (to check it is a QR and show whom it pays), and making one
 * for a payment (upi://pay with the payee, name, amount and the bill number as the note) that any UPI app can scan.
 */
public final class UpiQr {

    private UpiQr() {
    }

    /**
     * The text of the QR code in the picture, or null when there is none to read. Phone screenshots of payment QRs
     * are often small, tight to the edge and have a logo in the middle, so it also tries the picture enlarged on a
     * white border, with two ways of telling black from white.
     */
    public static String read(byte[] image) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(image));
        } catch (IOException e) {
            return null;
        }
        if (img == null) {
            return null;
        }
        // as it is; enlarged on white; and trimmed of a dark frame around the code (screenshots often have one)
        java.util.List<BufferedImage> tries = new java.util.ArrayList<>();
        tries.add(img);
        for (int scale : new int[]{2, 3}) {
            tries.add(padded(img, scale));
        }
        for (int trim : new int[]{2, 4, 6, 8, 10, 14}) {
            if (img.getWidth() > 4 * trim && img.getHeight() > 4 * trim) {
                tries.add(padded(img.getSubimage(trim, trim, img.getWidth() - 2 * trim, img.getHeight() - 2 * trim), 2));
            }
        }
        for (BufferedImage candidate : tries) {
            int w = candidate.getWidth();
            int h = candidate.getHeight();
            LuminanceSource source = new RGBLuminanceSource(w, h, candidate.getRGB(0, 0, w, h, null, 0, w));
            for (Binarizer binarizer : new Binarizer[]{new HybridBinarizer(source), new GlobalHistogramBinarizer(source),
                    new HybridBinarizer(source.invert())}) {
                try {
                    return new QRCodeReader().decode(new BinaryBitmap(binarizer), Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
                } catch (NotFoundException | ChecksumException | FormatException e) {
                    // try the next way
                }
            }
        }
        return null;
    }

    /** The picture enlarged, on a white border (the quiet zone a QR reader needs). */
    private static BufferedImage padded(BufferedImage img, int scale) {
        int border = 40 * scale;
        int w = img.getWidth() * scale;
        int h = img.getHeight() * scale;
        BufferedImage out = new BufferedImage(w + 2 * border, h + 2 * border, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img, border, border, w, h, null);
        g.dispose();
        return out;
    }

    /** The UPI ID a upi://pay link pays to (its pa=), or null. */
    public static String payee(String payload) {
        if (payload == null || !payload.toLowerCase().startsWith("upi://pay")) {
            return null;
        }
        int q = payload.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String part : payload.substring(q + 1).split("&")) {
            if (part.toLowerCase().startsWith("pa=")) {
                return URLDecoder.decode(part.substring(3), StandardCharsets.UTF_8).strip();
            }
        }
        return null;
    }

    /** upi://pay?pa=…&pn=…&am=…&cu=INR&tn=…; amount null = the payer types it. */
    public static String link(String upiId, String payeeName, BigDecimal amount, String note) {
        StringBuilder s = new StringBuilder("upi://pay?pa=").append(enc(upiId)).append("&pn=").append(enc(payeeName));
        if (amount != null && amount.signum() > 0) {
            s.append("&am=").append(amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        s.append("&cu=INR");
        if (note != null && !note.isBlank()) {
            s.append("&tn=").append(enc(note));
        }
        return s.toString();
    }

    /** A black-on-white QR code of the text as a PNG, size x size pixels. */
    public static byte[] png(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1));
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (WriterException e) {
            throw new IllegalArgumentException("The QR code could not be made", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
