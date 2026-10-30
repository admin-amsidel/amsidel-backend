/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.entity.BankLine;
import com.amsidel.backend.exception.ApiException;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankStatementReaderTest {

    @Test
    void anExcelStatementWithTitleRowsAndRealDates() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = book.createSheet("Statement");
            CreationHelper h = book.getCreationHelper();
            var dateStyle = book.createCellStyle();
            dateStyle.setDataFormat(h.createDataFormat().getFormat("dd/mm/yyyy"));
            s.createRow(0).createCell(0).setCellValue("HDFC BANK · Statement of account");
            Row head = s.createRow(3);
            String[] cols = {"Date", "Narration", "Chq./Ref.No.", "Value Dt", "Withdrawal Amt.", "Deposit Amt.", "Closing Balance"};
            for (int i = 0; i < cols.length; i++) {
                head.createCell(i).setCellValue(cols[i]);
            }
            Object[][] rows = {
                    {LocalDate.of(2026, 9, 1), "NEFT CR-KUMAR", "N123", null, null, 50000.0, 150000.0},
                    {LocalDate.of(2026, 9, 3), "ATM WDL", "", null, 2000.0, null, 148000.0},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(4 + r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof LocalDate d) {
                        row.createCell(c).setCellValue(d);
                        row.getCell(c).setCellStyle(dateStyle);
                    } else if (v instanceof Double n) {
                        row.createCell(c).setCellValue(n);
                    } else if (v instanceof String t) {
                        row.createCell(c).setCellValue(t);
                    }
                }
            }
            s.createRow(7).createCell(1).setCellValue("** End of statement **");
            book.write(out);
            xlsx = out.toByteArray();
        }
        BankStatementReader.Statement st = BankStatementReader.read("hdfc.xlsx", xlsx);
        assertThat(st.lines()).hasSize(2);
        assertThat(st.lines().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(st.lines().get(0).credit()).isEqualByComparingTo("50000");
        assertThat(st.lines().get(0).reference()).isEqualTo("N123");
        assertThat(st.lines().get(1).debit()).isEqualByComparingTo("2000");
        assertThat(st.opening()).isEqualByComparingTo("100000");
        assertThat(st.closing()).isEqualByComparingTo("148000");
        assertThat(st.warnings()).isEmpty();
    }

    @Test
    void oneAmountColumnWithCrDrAndAMissingRow() {
        String csv = """
                Transaction Date;Description;Amount;Dr/Cr;Balance
                01-Sep-2026;Opening;;;
                02-Sep-2026;UPI from Rossi;12,500.00;CR;62,500.00
                04-Sep-2026;Rent;10,000.00;DR;52,500.00
                05-Sep-2026;Fuel;1,000.00;DR;41,500.00
                """;
        BankStatementReader.Statement st = BankStatementReader.read("sbi.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(st.lines()).hasSize(3);
        assertThat(st.lines().get(0).credit()).isEqualByComparingTo("12500");
        assertThat(st.lines().get(1).debit()).isEqualByComparingTo("10000");
        assertThat(st.opening()).isEqualByComparingTo("50000");
        // 52,500 - 1,000 is not 41,500: a row is missing
        assertThat(st.warnings()).anyMatch(w -> w.contains("does not add up from 2026-09-05"));
    }

    @Test
    void notAStatement() {
        assertThatThrownBy(() -> BankStatementReader.read("x.csv", "Name,Phone\nA,1\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class).hasMessageContaining("No header row");
        assertThatThrownBy(() -> BankStatementReader.read("x.xlsx", new byte[]{1, 2, 3}))
                .isInstanceOf(ApiException.class).hasMessageContaining("not an Excel file");
    }

    @Test
    void amountsAndDatesAsBanksWriteThem() {
        assertThat(BankStatementReader.money("₹ 1,06,382.50 Cr")).isEqualByComparingTo("106382.50");
        assertThat(BankStatementReader.money("(1,200.00)")).isEqualByComparingTo("-1200");
        assertThat(BankStatementReader.money("-")).isNull();
        assertThat(BankStatementReader.date("03/09/26")).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(BankStatementReader.date("3 Sep 2026 10:15:00")).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(BankStatementReader.date("2026-09-03")).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void aDaysSalariesPaidInOneGoMatchOneBankLine() {
        BankLine l = new BankLine();
        l.setId(1L);
        l.setTxnDate(LocalDate.of(2026, 9, 30));
        l.setDebit(new BigDecimal("45000.00"));
        l.setCredit(BigDecimal.ZERO.setScale(2));
        List<ReconcileService.Move> moves = List.of(
                new ReconcileService.Move("SALARY", 1L, LocalDate.of(2026, 9, 30), false, new BigDecimal("25000.00"), "Salary · A", null, null, null, false, null),
                new ReconcileService.Move("SALARY", 2L, LocalDate.of(2026, 9, 30), false, new BigDecimal("20000.00"), "Salary · B", null, null, null, false, null),
                new ReconcileService.Move("SALARY", 3L, LocalDate.of(2026, 9, 30), false, new BigDecimal("9000.00"), "Salary · C (cash)", null, null, null, true, null));
        ReconcileService.Matching m = ReconcileService.match(List.of(l), moves);
        assertThat(m.matches().get(1L)).extracting(ReconcileService.Move::id).containsExactlyInAnyOrder(1L, 2L);
        assertThat(m.partly()).isEmpty();
    }
}
