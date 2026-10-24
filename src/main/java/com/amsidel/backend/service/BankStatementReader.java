package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;
import org.apache.poi.ss.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Reads a bank statement from Excel (.xlsx, .xls) or CSV, as banks give it: title rows, then a header row with the
 * date, description (narration, particulars), reference, credit (deposit) and debit (withdrawal) columns, or one
 * amount column with Cr / Dr, and the balance. Lines are put oldest first; the running balance is checked.
 */
public final class BankStatementReader {

    public record Line(LocalDate date, String description, String reference, BigDecimal credit, BigDecimal debit,
                       BigDecimal balance) {
    }

    public record Statement(List<Line> lines, BigDecimal opening, BigDecimal closing, List<String> warnings) {
    }

    private static final int MAX_LINES = 20000;
    private static final List<DateTimeFormatter> DATES = List.of(
            f("d/M/uuuu"), f("d-M-uuuu"), f("d.M.uuuu"), f("uuuu-M-d"), f("d/M/uu"), f("d-M-uu"),
            f("d-MMM-uuuu"), f("d MMM uuuu"), f("d-MMM-uu"), f("d MMM uu"), f("d/MMM/uuuu"), f("MMM d, uuuu"), f("d MMMM uuuu"));

    private BankStatementReader() {
    }

    private static DateTimeFormatter f(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH);
    }

    public static Statement read(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("The file is empty");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        List<List<Object>> rows = name.endsWith(".csv") || name.endsWith(".txt") ? csv(bytes) : sheet(bytes);
        return parse(rows);
    }

    // ---- cells ----

    private static List<List<Object>> sheet(byte[] bytes) {
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet s = book.getSheetAt(0);
            FormulaEvaluator eval = book.getCreationHelper().createFormulaEvaluator();
            List<List<Object>> rows = new ArrayList<>();
            for (int r = s.getFirstRowNum(); r <= s.getLastRowNum() && rows.size() <= MAX_LINES + 100; r++) {
                Row row = s.getRow(r);
                List<Object> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        cells.add(value(row.getCell(c), eval));
                    }
                }
                rows.add(cells);
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) {
                throw a;
            }
            throw ApiException.badRequest("This is not an Excel file we can read (.xlsx, .xls or .csv)");
        }
    }

    private static Object value(Cell cell, FormulaEvaluator eval) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? eval.evaluateFormulaCell(cell) : cell.getCellType();
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate()
                    : (Object) BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING -> blank(cell.getStringCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    static List<List<Object>> csv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        char sep = text.lines().limit(20).anyMatch(l -> l.indexOf(';') >= 0 && l.indexOf(',') < 0) ? ';' : ',';
        List<List<Object>> rows = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            List<Object> cells = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"') {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (ch == sep && !quoted) {
                    cells.add(blank(cur.toString()));
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
            cells.add(blank(cur.toString()));
            rows.add(cells);
        }
        return rows;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    // ---- columns ----

    private record Columns(int date, int description, int reference, int credit, int debit, int amount, int drCr, int balance) {
    }

    private static String key(Object o) {
        return o instanceof String s ? s.toLowerCase(Locale.ROOT).replaceAll("[^a-z/]", " ").replaceAll("\\s+", " ").strip() : "";
    }

    private static Columns columns(List<Object> row) {
        int date = -1, desc = -1, ref = -1, credit = -1, debit = -1, amount = -1, drCr = -1, balance = -1;
        for (int i = 0; i < row.size(); i++) {
            String k = key(row.get(i));
            if (k.isEmpty()) {
                continue;
            }
            if (k.contains("date")) {
                // the transaction (posting) date rather than the value date
                if (date < 0 || (k.contains("value") ? false : key(row.get(date)).contains("value"))) {
                    date = i;
                }
            } else if (k.contains("balance")) {
                if (balance < 0) {
                    balance = i;
                }
            } else if (k.equals("dr/cr") || k.equals("cr/dr") || k.equals("type") || k.contains("debit/credit") || k.contains("credit/debit")
                    || k.equals("dr cr") || k.equals("cr dr")) {
                drCr = i;
            } else if (k.contains("withdraw") || k.contains("debit") || k.equals("dr") || k.contains("dr amount") || k.equals("payments")) {
                debit = i;
            } else if (k.contains("deposit") || k.contains("credit") || k.equals("cr") || k.contains("cr amount") || k.equals("receipts")) {
                credit = i;
            } else if (k.contains("narration") || k.contains("description") || k.contains("particular") || k.contains("details")
                    || k.contains("remarks")) {
                if (desc < 0) {
                    desc = i;
                }
            } else if (k.contains("ref") || k.contains("cheque") || k.contains("chq") || k.contains("utr")) {
                if (ref < 0) {
                    ref = i;
                }
            } else if (k.contains("amount")) {
                amount = i;
            }
        }
        boolean money = (credit >= 0 && debit >= 0) || amount >= 0;
        return date >= 0 && money ? new Columns(date, desc, ref, credit, debit, amount, drCr, balance) : null;
    }

    // ---- lines ----

    static Statement parse(List<List<Object>> rows) {
        Columns cols = null;
        int start = 0;
        for (int r = 0; r < Math.min(rows.size(), 60) && cols == null; r++) {
            cols = columns(rows.get(r));
            start = r + 1;
        }
        if (cols == null) {
            throw ApiException.badRequest("No header row found: the file needs columns for the date, credit and debit"
                    + " (or one amount with Cr / Dr), and preferably the description and balance");
        }
        List<Line> lines = new ArrayList<>();
        BigDecimal openingRow = null;
        for (int r = start; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            LocalDate date = date(cell(row, cols.date()));
            String desc = text(cell(row, cols.description()));
            if (date == null) {
                if (desc != null && desc.toLowerCase(Locale.ROOT).contains("opening") && lines.isEmpty()) {
                    openingRow = money(cell(row, cols.balance()));
                }
                continue;
            }
            BigDecimal credit;
            BigDecimal debit;
            if (cols.credit() >= 0 && cols.debit() >= 0) {
                credit = money(cell(row, cols.credit()));
                debit = money(cell(row, cols.debit()));
            } else {
                BigDecimal amount = money(cell(row, cols.amount()));
                String sign = text(cell(row, cols.drCr()));
                String raw = text(cell(row, cols.amount()));
                boolean dr = sign != null ? sign.toLowerCase(Locale.ROOT).startsWith("d")
                        : (amount != null && amount.signum() < 0) || (raw != null && raw.toLowerCase(Locale.ROOT).contains("dr"));
                credit = dr ? null : amount;
                debit = dr ? amount : null;
            }
            credit = credit == null ? BigDecimal.ZERO.setScale(2) : credit.abs();
            debit = debit == null ? BigDecimal.ZERO.setScale(2) : debit.abs();
            if (credit.signum() == 0 && debit.signum() == 0) {
                if (desc != null && desc.toLowerCase(Locale.ROOT).contains("opening") && lines.isEmpty()) {
                    openingRow = money(cell(row, cols.balance()));
                }
                continue;
            }
            if (credit.signum() > 0 && debit.signum() > 0) {
                throw ApiException.badRequest("Row " + (r + 1) + " has both a credit and a debit");
            }
            if (lines.size() >= MAX_LINES) {
                throw ApiException.badRequest("At most " + MAX_LINES + " lines in one statement");
            }
            lines.add(new Line(date, cut(desc, 500), cut(text(cell(row, cols.reference())), 80), credit, debit,
                    cols.balance() < 0 ? null : money(cell(row, cols.balance()))));
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("No transactions found under the header row");
        }
        // newest first in the file: turn it round
        if (lines.getFirst().date().isAfter(lines.getLast().date())) {
            Collections.reverse(lines);
        }
        List<String> warnings = new ArrayList<>();
        Line first = lines.getFirst();
        BigDecimal opening = openingRow != null ? openingRow
                : first.balance() == null ? null : first.balance().subtract(first.credit()).add(first.debit());
        int off = 0;
        BigDecimal running = opening;
        for (Line l : lines) {
            if (running != null && l.balance() != null && running.add(l.credit()).subtract(l.debit()).compareTo(l.balance()) != 0) {
                if (off++ == 0) {
                    warnings.add("The balance does not add up from " + l.date() + " (" + (l.description() == null ? "" : l.description()) + ")");
                }
            }
            running = l.balance() != null ? l.balance() : running == null ? null : running.add(l.credit()).subtract(l.debit());
        }
        if (off > 1) {
            warnings.add("The running balance does not add up on " + off + " lines: check for missing rows");
        }
        if (cols.balance() < 0) {
            warnings.add("No balance column: type the bank balance in to tally it");
        }
        BigDecimal closing = lines.getLast().balance() != null ? lines.getLast().balance() : running;
        return new Statement(lines, opening, closing, warnings);
    }

    private static Object cell(List<Object> row, int i) {
        return i < 0 || i >= row.size() ? null : row.get(i);
    }

    private static String text(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        return blank(o.toString());
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static LocalDate date(Object o) {
        if (o instanceof LocalDate d) {
            return d;
        }
        if (!(o instanceof String s)) {
            return null;
        }
        String v = s.strip().split("\\s+\\d{1,2}:\\d{2}")[0].strip();
        for (DateTimeFormatter f : DATES) {
            try {
                LocalDate d = LocalDate.parse(v, f);
                if (d.getYear() < 100) {
                    d = d.plusYears(2000);
                }
                return d;
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        return null;
    }

    /** 12,500.00 / ₹ 12,500 / 12500.00 Cr / (1,200.00) / -1200 → the amount; blank or "-" → null. */
    static BigDecimal money(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b.setScale(2, RoundingMode.HALF_UP);
        }
        String s = o.toString().strip();
        boolean negative = s.startsWith("(") && s.endsWith(")") || s.startsWith("-");
        String digits = s.replaceAll("(?i)cr|dr|inr|rs\\.?|₹|,|\\s|\\(|\\)", "");
        if (digits.startsWith("-") || digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.isEmpty() || !digits.matches("\\d+(\\.\\d+)?")) {
            return null;
        }
        BigDecimal v = new BigDecimal(digits).setScale(2, RoundingMode.HALF_UP);
        return negative ? v.negate() : v;
    }
}
