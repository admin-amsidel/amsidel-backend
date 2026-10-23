package com.amsidel.backend.controller;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads an exported .xlsx back for the tests: sheet names, and each sheet's rows as the cells show them. */
final class XlsxRead {

    private XlsxRead() {
    }

    static List<String> sheets(byte[] bytes) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<String> names = new ArrayList<>();
            book.forEach(s -> names.add(s.getSheetName()));
            return names;
        }
    }

    /** Every row of the sheet, each cell as Excel shows it ("" when empty). */
    static List<List<String>> rows(byte[] bytes, String sheetName) throws Exception {
        DataFormatter shown = new DataFormatter(Locale.ENGLISH);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = book.getSheet(sheetName);
            List<List<String>> rows = new ArrayList<>();
            for (Row r : sheet) {
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < r.getLastCellNum(); i++) {
                    Cell c = r.getCell(i);
                    cells.add(c == null ? "" : shown.formatCellValue(c));
                }
                rows.add(cells);
            }
            return rows;
        }
    }

    /** The first row whose cells include all these values. */
    static List<String> rowWith(byte[] bytes, String sheetName, String... values) throws Exception {
        return rows(bytes, sheetName).stream().filter(r -> r.containsAll(List.of(values))).findFirst()
                .orElseThrow(() -> new AssertionError("No row in " + sheetName + " with " + List.of(values)));
    }
}
