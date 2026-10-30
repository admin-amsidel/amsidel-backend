/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;

import java.util.Locale;

/**
 * The paper a quotation or invoice is printed on: an A4 sheet, or a roll 7, 12 or 15 cm wide (restaurant and counter
 * printers), where the bill is only as long as its content so no paper is wasted.
 */
public enum PaperSize {
    A4(0, "A4"),
    MM70(70, "7 cm roll"),
    MM120(120, "12 cm roll"),
    MM150(150, "15 cm roll");

    private final int widthMm;
    private final String label;

    PaperSize(int widthMm, String label) {
        this.widthMm = widthMm;
        this.label = label;
    }

    public boolean isRoll() {
        return widthMm > 0;
    }

    /** The roll's width in PDF points (1 mm = 72 / 25.4 pt). */
    public float widthPoints() {
        return widthMm * 72f / 25.4f;
    }

    public int widthMm() {
        return widthMm;
    }

    public String label() {
        return label;
    }

    /** "A4", "70MM" / "70" / "7cm"... ; empty = A4. */
    public static PaperSize of(String value) {
        if (value == null || value.isBlank()) {
            return A4;
        }
        String v = value.strip().toUpperCase(Locale.ROOT).replace(" ", "");
        return switch (v) {
            case "A4" -> A4;
            case "70", "70MM", "MM70", "7CM" -> MM70;
            case "120", "120MM", "MM120", "12CM" -> MM120;
            case "150", "150MM", "MM150", "15CM" -> MM150;
            default -> throw ApiException.badRequest("Unknown paper size " + value + ": use A4, 70MM, 120MM or 150MM");
        };
    }
}
