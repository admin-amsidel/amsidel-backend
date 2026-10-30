/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Income & expenses and the P&L reconciler: an invoice of ₹10,000 paid ₹9,000 is found short and the ₹1,000 written
 * off as a discount (the invoice is then settled at ₹10,000); a bank statement is uploaded and its lines matched to
 * the payment and the expenses; what is not in the portal is recorded from the line, until the bank tallies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=accounts")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountsTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final DateTimeFormatter BANK_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private String staff;
    private long dw;
    private String customer;
    private String item;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.getFirst();
        // an accountant of DW with Income & expenses and the reconciler (not an admin)
        String staffId = idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Anu Accounts\",\"userId\":\"ac.anu\",\"password\":\"ab@123\",\"role\":\"EMPLOYEE\",\"brandId\":%d,\"phone\":\"9847000002\",\"joinedDate\":\"2026-01-05\"}"
                        .formatted(dw))).andExpect(status().isCreated()));
        mockMvc.perform(put("/api/menu-access/EMPLOYEE/" + staffId).header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkpoints\":[\"11601\",\"11602\"]}")).andExpect(status().isOk());
        staff = token(changePin(token(login("ac.anu", "ab@123")), "ab@123", "cd@456"));
        customer = idOf(json(post("/api/trade/customers"), owner, "{\"brandId\":%d,\"name\":\"Varghese Villa\",\"phone\":\"9847033333\"}".formatted(dw)));
        List<Integer> types = JsonPath.read(body(mockMvc.perform(get("/api/trade/types").param("brandId", String.valueOf(dw))
                .header("Authorization", auth(owner)))), "$[?(@.code == 'UPVC')].id");
        item = idOf(json(post("/api/trade/items"), owner, """
                {"brandId":%d,"typeId":%d,"code":"UPVC-CW-01","name":"uPVC casement window","supplierPrice":4000,"mrp":12000,
                 "showroomPrice":10000,"minSellPrice":9000,"openingStock":20}
                """.formatted(dw, types.getFirst())));
    }

    @Test
    void shortPaymentWrittenOffAndTheBankTallied() throws Exception {
        LocalDate d0 = TODAY.minusDays(10);
        LocalDate d1 = TODAY.minusDays(5);
        LocalDate d2 = TODAY.minusDays(3);
        LocalDate d3 = TODAY.minusDays(2);

        // an invoice of ₹10,000; the customer pays only ₹9,000
        String invoice = idOf(json(post("/api/trade/sales"), owner, """
                {"kind":"INVOICE","brandId":%d,"customerId":%s,"docDate":"%s","gstRate":0,"lines":[{"itemId":%s,"name":"w","quantity":1,"rate":10000}]}
                """.formatted(dw, customer, d0, item)));
        mockMvc.perform(post("/api/trade/sales/" + invoice + "/issue").header("Authorization", auth(owner))).andExpect(status().isOk());
        json(post("/api/trade/sales/" + invoice + "/payments"), owner,
                "{\"paidOn\":\"%s\",\"amount\":9000,\"mode\":\"BANK\",\"transactionId\":\"UTR998877\"}".formatted(d1))
                .andExpect(jsonPath("$.totals.due").value(1000.0));

        // an expense of the bill (transport by UPI), by the accountant
        json(post("/api/accounts/entries"), staff, """
                {"category":"TRANSPORT","entryDate":"%s","amount":2500,"mode":"UPI","partyName":"Murugan Transport",
                 "description":"Windows to the site","invoiceId":%s}
                """.formatted(d2, invoice)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number", matchesPattern("DWEXP-\\d{6}-\\d{3}")))
                .andExpect(jsonPath("$.customerName").value("Varghese Villa"))
                .andExpect(jsonPath("$.createdByName").value("Anu Accounts"))
                .andExpect(jsonPath("$.history[0].action").value("CREATED"));
        // money with no money moved is only a discount on an invoice
        json(post("/api/accounts/entries"), staff, "{\"category\":\"RENT\",\"entryDate\":\"%s\",\"amount\":100,\"description\":\"x\"}".formatted(d2))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("how it was paid")));

        // the bank statement (newest first, as many banks give it), with title rows above the header
        String csv = """
                Statement of account,,,,,
                Account: 50100012345662,,,,,
                Txn Date,Narration,Chq./Ref.No.,Withdrawal Amt.,Deposit Amt.,Closing Balance
                %s,BANK CHARGES INCL GST,,118.00,,"1,06,382.00"
                %s,UPI/MURUGAN TRANSPORT/PAY,UPI-55,"2,500.00",,"1,06,500.00"
                %s,NEFT/UTR998877/VARGHESE VILLA,,,"9,000.00","1,09,000.00"
                %s,CAPITAL FROM DIRECTOR SIBIN,,,"1,00,000.00","1,00,000.00"
                """.formatted(BANK_DATE.format(d3), BANK_DATE.format(d2), BANK_DATE.format(d1), BANK_DATE.format(d0));
        mockMvc.perform(multipart("/api/accounts/bank/statements").file(new MockMultipartFile("file", "sep.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("brandId", String.valueOf(dw)).header("Authorization", auth(staff)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines").value(4))
                .andExpect(jsonPath("$.fromDate").value(d0.toString()))
                .andExpect(jsonPath("$.openingBalance").value(0.0))
                .andExpect(jsonPath("$.closingBalance").value(106382.0))
                .andExpect(jsonPath("$.warnings.length()").value(0));
        // the same days again are refused
        mockMvc.perform(multipart("/api/accounts/bank/statements").file(new MockMultipartFile("file", "sep.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .param("brandId", String.valueOf(dw)).header("Authorization", auth(staff))).andExpect(status().isConflict());

        // the payment and the transport are found in the bank; the capital and the bank charges are not in the portal
        String report = body(reconcile(staff, d0, d3, "WEEK")
                .andExpect(jsonPath("$.counts.bankLines").value(4))
                .andExpect(jsonPath("$.counts.matched").value(2))
                .andExpect(jsonPath("$.bankLines[?(@.description =~ /NEFT.*/)].matches[0].type").value(hasItem("CUSTOMER_PAYMENT")))
                .andExpect(jsonPath("$.bankLines[?(@.description =~ /UPI.*/)].matches[0].type").value(hasItem("ENTRY")))
                .andExpect(jsonPath("$.total.tally.opening").value(0.0))
                .andExpect(jsonPath("$.total.tally.actual").value(106382.0))
                .andExpect(jsonPath("$.total.tally.expected").value(6500.0))
                .andExpect(jsonPath("$.shortPaid[0].number", startsWith("DWSLD-")))
                .andExpect(jsonPath("$.shortPaid[0].customerName").value("Varghese Villa"))
                .andExpect(jsonPath("$.shortPaid[0].due").value(1000.0)));
        Integer capitalLine = JsonPath.<List<Integer>>read(report, "$.bankLines[?(@.description =~ /CAPITAL.*/)].id").getFirst();
        Integer chargesLine = JsonPath.<List<Integer>>read(report, "$.bankLines[?(@.description =~ /BANK CHARGES.*/)].id").getFirst();

        // recorded from the bank lines: the capital and the charges; then the bank tallies
        json(post("/api/accounts/entries"), staff, """
                {"category":"CAPITAL","entryDate":"%s","amount":100000,"mode":"BANK","partyName":"Sibin","description":"Capital","bankLineId":%d}
                """.formatted(d0, capitalLine)).andExpect(jsonPath("$.number", matchesPattern("DWINC-\\d{6}-\\d{3}")));
        json(post("/api/accounts/entries"), staff, """
                {"category":"BANK_CHARGES","entryDate":"%s","amount":118,"mode":"BANK","description":"Charges","bankLineId":%d}
                """.formatted(d3, capitalLine)).andExpect(status().isBadRequest());
        json(post("/api/accounts/entries"), staff, """
                {"category":"BANK_CHARGES","entryDate":"%s","amount":118,"mode":"BANK","description":"Charges","bankLineId":%d}
                """.formatted(d3, chargesLine)).andExpect(status().isCreated());
        reconcile(staff, d0, d3, "MONTH")
                .andExpect(jsonPath("$.counts.matched").value(4))
                .andExpect(jsonPath("$.counts.notInBank").value(0))
                .andExpect(jsonPath("$.total.tally.expected").value(106382.0))
                .andExpect(jsonPath("$.total.tally.difference").value(0.0))
                .andExpect(jsonPath("$.total.flow.funding").value(100000.0))
                .andExpect(jsonPath("$.total.flow.customerReceipts").value(9000.0));
        // a period wider than the statement is tallied over the days whose balances are known
        reconcile(staff, d0.minusDays(4), TODAY.plusDays(6), "MONTH")
                .andExpect(jsonPath("$.total.tally.tallyFrom").value(d0.toString()))
                .andExpect(jsonPath("$.total.tally.tallyTo").value(d3.toString()))
                .andExpect(jsonPath("$.total.tally.difference").value(0.0));

        // the ₹1,000 short is written off: an expense of the customer and bill; the invoice is settled at ₹10,000
        json(post("/api/accounts/invoices/" + invoice + "/write-off"), staff, "{\"entryDate\":\"%s\",\"remarks\":\"Customer asked for a discount\"}".formatted(TODAY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("DISCOUNT"))
                .andExpect(jsonPath("$.amount").value(1000.0))
                .andExpect(jsonPath("$.writeOff").value(true))
                .andExpect(jsonPath("$.mode").value(nullValue()));
        String entryId = JsonPath.read(body(mockMvc.perform(get("/api/accounts/entries").param("brandId", String.valueOf(dw))
                .param("from", TODAY.toString()).param("to", TODAY.toString()).header("Authorization", auth(staff)))), "$[0].id").toString();
        mockMvc.perform(get("/api/trade/sales/" + invoice).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.totals.total").value(10000.0))
                .andExpect(jsonPath("$.totals.paid").value(9000.0))
                .andExpect(jsonPath("$.totals.writtenOff").value(1000.0))
                .andExpect(jsonPath("$.totals.due").value(0.0))
                .andExpect(jsonPath("$.payStatus").value("PAID"))
                .andExpect(jsonPath("$.history[0].action").value("WRITE_OFF"));
        mockMvc.perform(get("/api/trade/sales/" + invoice + "/pdf").header("Authorization", auth(owner))).andExpect(status().isOk());
        reconcile(staff, d0, TODAY, "MONTH")
                .andExpect(jsonPath("$.shortPaid.length()").value(0))
                .andExpect(jsonPath("$.total.pnl.sales").value(10000.0))
                .andExpect(jsonPath("$.total.pnl.discounts").value(1000.0))
                .andExpect(jsonPath("$.total.pnl.otherExpenses").value(2618.0))
                .andExpect(jsonPath("$.total.pnl.profit").value(6382.0))
                // the discount moved no money: the bank still tallies
                .andExpect(jsonPath("$.total.flow.moneyOut").value(2618.0));
        // no more than is due can be written off; staff cannot delete, an owner can (the invoice is due again)
        json(post("/api/accounts/invoices/" + invoice + "/write-off"), staff, "{\"entryDate\":\"%s\"}".formatted(TODAY)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/accounts/entries/" + entryId).header("Authorization", auth(staff))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/accounts/entries/" + entryId).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/trade/sales/" + invoice).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.totals.due").value(1000.0)).andExpect(jsonPath("$.payStatus").value("PARTLY_PAID"));

        // a typed-in balance; the Excel of it all
        String month = java.time.YearMonth.from(TODAY).toString();
        String balanceId = idOf(json(post("/api/accounts/bank/balances"), staff, "{\"brandId\":%d,\"month\":\"%s\",\"balance\":100000,\"note\":\"Passbook\"}".formatted(dw, month))
                .andExpect(jsonPath("$.balance").value(100000.0)).andExpect(jsonPath("$.asOf").value(TODAY.toString()))
                .andExpect(jsonPath("$.enteredByName").value("Anu Accounts")).andExpect(jsonPath("$.updatedByName").value(nullValue())));
        // the same month again: replaced, not added; who changed it is kept
        json(post("/api/accounts/bank/balances"), owner, "{\"brandId\":%d,\"month\":\"%s\",\"balance\":106382,\"note\":\"Net banking\"}".formatted(dw, month))
                .andExpect(jsonPath("$.id").value(Integer.parseInt(balanceId))).andExpect(jsonPath("$.balance").value(106382.0))
                .andExpect(jsonPath("$.enteredByName").value("Anu Accounts")).andExpect(jsonPath("$.updatedByName").value("Amsidel Owner"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        mockMvc.perform(get("/api/accounts/bank/balances").param("brandId", String.valueOf(dw)).header("Authorization", auth(staff)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].month").value(month));
        json(post("/api/accounts/bank/balances"), staff, "{\"brandId\":%d,\"month\":\"%s\",\"balance\":1}".formatted(dw, java.time.YearMonth.from(TODAY).plusMonths(1)))
                .andExpect(status().isBadRequest());
        reconcile(staff, d0, TODAY, "WEEK").andExpect(jsonPath("$.total.tally.actual").value(106382.0));
        mockMvc.perform(get("/api/accounts/reconcile/excel").param("brandId", String.valueOf(dw)).param("from", d0.toString())
                        .param("to", TODAY.toString()).header("Authorization", auth(staff)))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", containsString("PnL-DW-")));
        mockMvc.perform(get("/api/accounts/bank/template").header("Authorization", auth(staff))).andExpect(status().isOk());
    }

    @Test
    void ownersAndAdminsSetUpTheCategories() throws Exception {
        // dated well before the other tests' periods, and removed at the end
        LocalDate OLD = TODAY.minusYears(2);
        // the categories the portal started with, money in first
        mockMvc.perform(get("/api/accounts/categories").header("Authorization", auth(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].income").value(true))
                .andExpect(jsonPath("$[?(@.code == 'TRANSPORT')].label").value(hasItem("Transportation")))
                .andExpect(jsonPath("$[?(@.code == 'DISCOUNT')].locked").value(hasItem(true)));
        // the accountant may use them, not change them
        json(post("/api/accounts/categories"), staff, "{\"label\":\"Office supplies\",\"income\":false}").andExpect(status().isForbidden());

        String code = JsonPath.read(body(json(post("/api/accounts/categories"), owner, "{\"label\":\"Office supplies\",\"income\":false}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("OFFICE_SUPPLIES"))
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.used").value(false))), "$.code");
        json(post("/api/accounts/categories"), owner, "{\"label\":\"office SUPPLIES\",\"income\":false}").andExpect(status().isConflict());
        json(post("/api/accounts/categories"), owner, "{\"label\":\"Scrap sales\",\"income\":true}")
                .andExpect(jsonPath("$.code").value("SCRAP_SALES")).andExpect(jsonPath("$.income").value(true));

        // used for an expense; the reconciler shows it as its own line
        String entry = idOf(json(post("/api/accounts/entries"), staff, """
                {"category":"%s","entryDate":"%s","amount":640,"mode":"CASH","description":"Printer paper"}
                """.formatted(code, OLD)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryLabel").value("Office supplies")).andExpect(jsonPath("$.number", containsString("EXP"))));
        mockMvc.perform(get("/api/accounts/reconcile").param("brandId", String.valueOf(dw)).param("from", OLD.toString()).param("to", OLD.toString())
                        .param("groupBy", "MONTH").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.total.flow.outParts[?(@.key == '%s')].amount".formatted(code)).value(hasItem(640.0)));

        // renamed (the entry follows); in use, it cannot turn into income or be deleted, only switched off
        json(put("/api/accounts/categories/" + code), owner, "{\"label\":\"Stationery & office supplies\"}")
                .andExpect(jsonPath("$.label").value("Stationery & office supplies")).andExpect(jsonPath("$.used").value(true));
        mockMvc.perform(get("/api/accounts/entries/" + entry).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.categoryLabel").value("Stationery & office supplies"));
        json(put("/api/accounts/categories/" + code), owner, "{\"label\":\"Stationery\",\"income\":true}").andExpect(status().isConflict());
        mockMvc.perform(delete("/api/accounts/categories/" + code).header("Authorization", auth(owner))).andExpect(status().isConflict());
        json(put("/api/accounts/categories/" + code), owner, "{\"label\":\"Stationery & office supplies\",\"active\":false}")
                .andExpect(jsonPath("$.active").value(false));
        // switched off: not for new entries, but the old one still saves
        json(post("/api/accounts/entries"), staff, """
                {"category":"%s","entryDate":"%s","amount":100,"mode":"CASH","description":"Pens"}
                """.formatted(code, OLD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("switched off")));
        json(put("/api/accounts/entries/" + entry), staff, """
                {"category":"%s","entryDate":"%s","amount":650,"mode":"CASH","description":"Printer paper"}
                """.formatted(code, OLD)).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(650.0));

        // the discount stays as it is, apart from its name; an unused category can be deleted
        json(put("/api/accounts/categories/DISCOUNT"), owner, "{\"label\":\"Discount\",\"active\":false}").andExpect(status().isBadRequest());
        json(put("/api/accounts/categories/DISCOUNT"), owner, "{\"label\":\"Discount allowed\"}").andExpect(jsonPath("$.label").value("Discount allowed"));
        mockMvc.perform(delete("/api/accounts/categories/DISCOUNT").header("Authorization", auth(owner))).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/accounts/entries/" + entry).header("Authorization", auth(owner))).andExpect(status().is2xxSuccessful());
        mockMvc.perform(delete("/api/accounts/categories/SCRAP_SALES").header("Authorization", auth(owner))).andExpect(status().isNoContent());
        json(put("/api/accounts/categories/DISCOUNT"), owner, "{\"label\":\"Discount allowed / short payment\"}").andExpect(status().isOk());
    }

    @Test
    void theReconcilerIsNotForEveryone() throws Exception {
        idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ravi Sales\",\"userId\":\"ac.ravi\",\"password\":\"ab@123\",\"role\":\"EMPLOYEE\",\"brandId\":%d,\"phone\":\"9847000003\",\"joinedDate\":\"2026-01-05\"}"
                        .formatted(dw))).andExpect(status().isCreated()));
        String plain = token(changePin(token(login("ac.ravi", "ab@123")), "ab@123", "cd@456"));
        mockMvc.perform(get("/api/accounts/reconcile").param("from", TODAY.toString()).param("to", TODAY.toString())
                .header("Authorization", auth(plain))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/accounts/entries").param("from", TODAY.toString()).param("to", TODAY.toString())
                .header("Authorization", auth(plain))).andExpect(status().isForbidden());
        // an owner sees all brands together: the bank tally needs one brand
        mockMvc.perform(get("/api/accounts/reconcile").param("from", TODAY.toString()).param("to", TODAY.toString())
                .header("Authorization", auth(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0]", containsString("Choose a brand")));
    }

    private ResultActions reconcile(String token, LocalDate from, LocalDate to, String groupBy) throws Exception {
        return mockMvc.perform(get("/api/accounts/reconcile").param("brandId", String.valueOf(dw)).param("from", from.toString())
                .param("to", to.toString()).param("groupBy", groupBy).header("Authorization", auth(token))).andExpect(status().isOk());
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private ResultActions changePin(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private static String idOf(ResultActions result) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), "$.id"));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String token(ResultActions result) throws Exception {
        return JsonPath.read(body(result), "$.token");
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
