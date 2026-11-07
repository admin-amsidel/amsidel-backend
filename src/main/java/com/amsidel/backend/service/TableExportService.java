/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.TableExportRequest;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Writes a list from the screen as an Excel file, in the same look as the portal's other exports. */
@Service
@RequiredArgsConstructor
public class TableExportService {

    /** What one Excel cell can hold is 32767 characters. */
    private static final int MAX_CELL = 32_000;
    private static final int MIN_WIDTH = 10;
    private static final int MAX_WIDTH = 60;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private final TimeZones timeZones;

    public ExportService.Export build(AuthUser me, TableExportRequest request) {
        List<String> columns = request.columns();
        int[] widths = new int[columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.max(MIN_WIDTH, length(columns.get(i)) + 2);
        }
        Object[][] rows = new Object[request.rows().size()][];
        for (int r = 0; r < rows.length; r++) {
            List<Object> row = request.rows().get(r);
            if (row == null || row.size() != columns.size()) {
                throw ApiException.badRequest("Row " + (r + 1) + " does not have " + columns.size() + " cells");
            }
            rows[r] = new Object[row.size()];
            for (int c = 0; c < row.size(); c++) {
                Object value = cell(row.get(c));
                rows[r][c] = value;
                widths[c] = Math.max(widths[c], Math.min(MAX_WIDTH + 1, length(value) + 2));
            }
        }
        // long text wraps at the widest a column goes; ExcelBook takes a negative width to mean that
        for (int i = 0; i < widths.length; i++) {
            widths[i] = widths[i] > MAX_WIDTH ? -MAX_WIDTH : widths[i];
        }
        String title = request.title().strip();
        LocalDateTime now = LocalDateTime.now(timeZones.of(me));
        String note = rows.length + (rows.length == 1 ? " row" : " rows") + ", as on the screen. Exported on " + STAMP.format(now) + " by " + me.userId() + ".";
        try (ExcelBook book = new ExcelBook()) {
            ExcelBook.Table table = book.sheet(title, title, note, columns.stream().map(c -> c == null ? "" : c).toArray(String[]::new), widths);
            for (Object[] row : rows) {
                table.row(row);
            }
            table.done();
            return new ExportService.Export(fileName(title) + "_" + now.toLocalDate() + ".xlsx", book.bytes());
        }
    }

    /** Text stays text in the file, so a value starting with = is never run as a formula. */
    private static Object cell(Object value) {
        if (value == null || value instanceof Integer || value instanceof Long || value instanceof Double) {
            return value;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        String text = value.toString();
        return text.length() > MAX_CELL ? text.substring(0, MAX_CELL) : text;
    }

    private static int length(Object value) {
        return value == null ? 0 : value.toString().length();
    }

    private static String fileName(String title) {
        String name = title.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        return name.isEmpty() ? "Export" : name;
    }
}
