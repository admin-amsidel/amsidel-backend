/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.ProductItem;
import com.amsidel.backend.exception.ApiException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * An item's QR label: everything about the item but its stock (brand, code, name, type, unit, HSN, sizes, details,
 * the supplier's code and the prices), as JSON with "t": "AMSIDEL-ITEM". Read back when stock is received by scanning
 * (a code the brand does not have becomes a new item) and when billing.
 */
public final class ItemQr {

    public static final String KIND = "AMSIDEL-ITEM";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ItemQr() {
    }

    /** What a label holds, read back. */
    public record Label(String brand, String code, String name, String type, String typeName, String unit, String hsn, String sizes,
                        String details, String supplierItemCode, BigDecimal supplierPrice, BigDecimal mrp, BigDecimal showroomPrice,
                        BigDecimal minSellPrice) {
    }

    public static String payload(ProductItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", KIND);
        m.put("v", 1);
        m.put("brand", i.getBrand().getCode());
        m.put("code", i.getCode());
        m.put("name", i.getName());
        m.put("type", i.getType().getCode());
        m.put("typeName", i.getType().getName());
        m.put("unit", i.getUnit());
        putIf(m, "hsn", i.getHsnCode());
        putIf(m, "sizes", i.getSizes());
        putIf(m, "details", i.getDetails());
        putIf(m, "supplierItemCode", i.getSupplierItemCode());
        m.put("supplierPrice", i.getSupplierPrice());
        m.put("mrp", i.getMrp());
        m.put("showroomPrice", i.getShowroomPrice());
        m.put("minSellPrice", i.getMinSellPrice());
        return JSON.writeValueAsString(m);
    }

    /** The label's details; 400 when the text is not an item label. */
    public static Label read(String payload) {
        JsonNode n;
        try {
            n = JSON.readTree(payload);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("This QR code is not an item label");
        }
        if (n == null || !KIND.equals(text(n, "t")) || text(n, "code") == null || text(n, "name") == null) {
            throw ApiException.badRequest("This QR code is not an item label");
        }
        String code = text(n, "code").toUpperCase(Locale.ROOT);
        if (!code.matches("^[A-Z0-9][A-Z0-9_-]{0,29}$")) {
            throw ApiException.badRequest("The label's item code " + code + " is not valid");
        }
        return new Label(text(n, "brand"), code, cut(text(n, "name"), 150), text(n, "type"), text(n, "typeName"), text(n, "unit"),
                text(n, "hsn"), text(n, "sizes"), cut(text(n, "details"), 1000), text(n, "supplierItemCode"), amount(n, "supplierPrice"),
                amount(n, "mrp"), amount(n, "showroomPrice"), amount(n, "minSellPrice"));
    }

    /** A black-on-white QR code of the label (UTF-8, so names in any script survive), size x size pixels. */
    public static byte[] png(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("The QR code could not be made", e);
        }
    }

    private static void putIf(Map<String, Object> m, String key, String value) {
        if (value != null && !value.isBlank()) {
            m.put(key, value);
        }
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString().strip();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal amount(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal b = new BigDecimal(v.asString().strip());
            return b.signum() < 0 ? BigDecimal.ZERO : b.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
