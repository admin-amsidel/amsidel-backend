/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * A small Excel (.xlsx) writer for the exports: each sheet has a title, a line under it, then a table with a blue
 * header row that stays in view and has filters. Dates, times and hours are real Excel values (they sort and add
 * up). Rows are streamed, so a year of data for everyone stays light on memory.
 */
public final class ExcelBook implements AutoCloseable {

    /** Hours with two decimals: 7.50. */
    public record Hours(double value) {
        public static Hours of(BigDecimal b) {
            return new Hours(b == null ? 0 : b.doubleValue());
        }

        public static Hours ofMinutes(long minutes) {
            return new Hours(Math.round(minutes / 60.0 * 100) / 100.0);
        }
    }

    private static final byte[] BLUE = {(byte) 0x1d, (byte) 0x5f, (byte) 0xa3};
    private static final byte[] PALE = {(byte) 0xe3, (byte) 0xee, (byte) 0xfa};

    private final SXSSFWorkbook book = new SXSSFWorkbook(200);
    private final CellStyle title;
    private final CellStyle note;
    private final CellStyle header;
    private final CellStyle text;
    private final CellStyle wrap;
    private final CellStyle date;
    private final CellStyle time;
    private final CellStyle dateTime;
    private final CellStyle hours;
    private final CellStyle number;
    private final CellStyle bold;

    public ExcelBook() {
        var formats = book.createDataFormat();
        XSSFFont titleFont = (XSSFFont) book.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleFont.setColor(new XSSFColor(BLUE, null));
        title = book.createCellStyle();
        title.setFont(titleFont);

        Font grey = book.createFont();
        grey.setItalic(true);
        grey.setColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_50_PERCENT.getIndex());
        note = book.createCellStyle();
        note.setFont(grey);

        XSSFFont headerFont = (XSSFFont) book.createFont();
        headerFont.setBold(true);
        headerFont.setColor(new XSSFColor(BLUE, null));
        header = book.createCellStyle();
        header.setFont(headerFont);
        ((org.apache.poi.xssf.usermodel.XSSFCellStyle) header).setFillForegroundColor(new XSSFColor(PALE, null));
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setBorderBottom(BorderStyle.MEDIUM);
        ((org.apache.poi.xssf.usermodel.XSSFCellStyle) header).setBottomBorderColor(new XSSFColor(BLUE, null));
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setWrapText(true);

        text = book.createCellStyle();
        text.setVerticalAlignment(VerticalAlignment.TOP);
        wrap = book.createCellStyle();
        wrap.setVerticalAlignment(VerticalAlignment.TOP);
        wrap.setWrapText(true);
        date = styled(formats.getFormat("dd-mmm-yyyy"));
        time = styled(formats.getFormat("hh:mm AM/PM"));
        dateTime = styled(formats.getFormat("dd-mmm-yyyy hh:mm AM/PM"));
        hours = styled(formats.getFormat("0.00"));
        number = styled(formats.getFormat("0"));
        Font boldFont = book.createFont();
        boldFont.setBold(true);
        bold = book.createCellStyle();
        bold.setFont(boldFont);
    }

    private CellStyle styled(short format) {
        CellStyle s = book.createCellStyle();
        s.setDataFormat(format);
        s.setVerticalAlignment(VerticalAlignment.TOP);
        return s;
    }

    /**
     * A new sheet: the title and note on top, then the header row. widths are in characters; a column whose width
     * is negative wraps its text.
     */
    public Table sheet(String name, String titleText, String noteText, String[] headers, int[] widths) {
        Sheet sheet = book.createSheet(WorkbookUtil.createSafeSheetName(name));
        Row t = sheet.createRow(0);
        cell(t, 0, titleText, title);
        Row n = sheet.createRow(1);
        cell(n, 0, noteText, note);
        Row h = sheet.createRow(3);
        h.setHeightInPoints(30);
        boolean[] wraps = new boolean[headers.length];
        for (int i = 0; i < headers.length; i++) {
            cell(h, i, headers[i], header);
            wraps[i] = widths[i] < 0;
            sheet.setColumnWidth(i, Math.min(255, Math.abs(widths[i])) * 256);
        }
        sheet.createFreezePane(2, 4);
        return new Table(sheet, headers.length, wraps);
    }

    /** One table: add rows, then it puts the filters on the header. */
    public final class Table {
        private final Sheet sheet;
        private final int columns;
        private final boolean[] wraps;
        private int next = 4;

        private Table(Sheet sheet, int columns, boolean[] wraps) {
            this.sheet = sheet;
            this.columns = columns;
            this.wraps = wraps;
        }

        /** Strings, numbers, Hours, LocalDate, LocalTime and LocalDateTime; null leaves the cell empty. */
        public void row(Object... values) {
            Row r = sheet.createRow(next++);
            for (int i = 0; i < values.length; i++) {
                Object v = values[i];
                if (v == null) {
                    continue;
                }
                switch (v) {
                    case Hours h -> number(r, i, h.value(), hours);
                    case Integer n -> number(r, i, n, number);
                    case Long n -> number(r, i, n, number);
                    case Double n -> number(r, i, n, hours);
                    case BigDecimal n -> number(r, i, n.doubleValue(), hours);
                    case LocalDate d -> {
                        Cell c = r.createCell(i);
                        c.setCellValue(d);
                        c.setCellStyle(date);
                    }
                    case LocalDateTime d -> {
                        Cell c = r.createCell(i);
                        c.setCellValue(d);
                        c.setCellStyle(dateTime);
                    }
                    case LocalTime t -> number(r, i, t.toSecondOfDay() / 86400.0, time);
                    default -> cell(r, i, v.toString(), wraps[i] ? wrap : text);
                }
            }
        }

        /** A bold row under the table (totals); not part of the filtered rows. */
        public void totals(Object... values) {
            int filterEnd = next - 1;
            row(values);
            Row r = sheet.getRow(next - 1);
            for (Cell c : r) {
                CellStyle s = book.createCellStyle();
                s.cloneStyleFrom(c.getCellStyle());
                s.setFont(book.getFontAt(bold.getFontIndex()));
                s.setBorderTop(BorderStyle.THIN);
                c.setCellStyle(s);
            }
            filters(filterEnd);
        }

        public void done() {
            filters(next - 1);
        }

        private void filters(int lastRow) {
            if (lastRow >= 3) {
                sheet.setAutoFilter(new CellRangeAddress(3, Math.max(3, lastRow), 0, columns - 1));
            }
        }
    }

    public byte[] bytes() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the temporary files of the streamed rows. */
    @Override
    public void close() {
        try {
            book.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void cell(Row r, int i, String value, CellStyle style) {
        Cell c = r.createCell(i);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static void number(Row r, int i, double value, CellStyle style) {
        Cell c = r.createCell(i);
        c.setCellValue(value);
        c.setCellStyle(style);
    }
}
