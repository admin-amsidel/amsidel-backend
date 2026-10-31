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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The product portal: a supplier and an item with opening stock; a purchase bill received into stock; a customer,
 * a quotation made into an invoice, issued (stock out), paid, refunded and cancelled (stock back); reports, PDF and
 * Excel; who may see what.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=trade")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradeTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String owner;
    private long dw;
    private int upvc;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.getFirst();
        // the Doors & Windows product types are there from the start
        List<Integer> types = JsonPath.read(body(mockMvc.perform(get("/api/trade/types").param("brandId", String.valueOf(dw))
                .header("Authorization", auth(owner)))), "$[?(@.code == 'UPVC')].id");
        upvc = types.getFirst();
    }

    @Test
    void buyStockSellPayRefundAndCancel() throws Exception {
        // a supplier (code made for it) and an item with opening stock
        String supplier = idOf(json(post("/api/trade/suppliers"), owner, """
                {"brandId":%d,"name":"Fenesta Kochi","phone":"+91 98470 11111","gstNumber":"32abcde1234f1z5",
                 "bankAccount":"50100012345662","ifsc":"hdfc0001234","upi":"fenesta@okhdfc","contactPerson":"Ravi"}
                """.formatted(dw)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", matchesPattern("DWS-\\d{3}")))
                .andExpect(jsonPath("$.gstNumber").value("32ABCDE1234F1Z5")));
        String item = idOf(json(post("/api/trade/items"), owner, """
                {"brandId":%d,"typeId":%d,"supplierId":%s,"code":"upvc-sw-01","name":"uPVC sliding window",
                 "sizes":"6 * 4, 8x7","supplierPrice":1000,"mrp":3200,"showroomPrice":2800,"minSellPrice":2200,
                 "reorderLevel":3,"openingStock":5}
                """.formatted(dw, upvc, supplier)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("UPVC-SW-01"))
                .andExpect(jsonPath("$.sizes").value("6x4, 8x7"))
                .andExpect(jsonPath("$.onHand").value(5.0)));

        // bought: 10 at 1200 + 18% GST; received into stock at the new price
        String bill = idOf(json(post("/api/trade/purchases"), owner, """
                {"brandId":%d,"supplierId":%s,"supplierBillNo":"FN/889","billDate":"%s","gstRate":18,
                 "lines":[{"itemId":%s,"quantity":10,"rate":1200}]}
                """.formatted(dw, supplier, TODAY, item)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number", matchesPattern("DWPUR-\\d{6}-\\d{3}")))
                .andExpect(jsonPath("$.totals.taxable").value(12000.0))
                .andExpect(jsonPath("$.totals.cgst").value(1080.0))
                .andExpect(jsonPath("$.totals.total").value(14160.0)));
        mockMvc.perform(post("/api/trade/purchases/" + bill + "/receive").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        mockMvc.perform(get("/api/trade/items/" + item).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.item.onHand").value(15.0))
                .andExpect(jsonPath("$.item.supplierPrice").value(1200.0))
                .andExpect(jsonPath("$.moves[0].kind").value("PURCHASE"))
                .andExpect(jsonPath("$.moves[0].balanceAfter").value(15.0));
        json(post("/api/trade/purchases/" + bill + "/payments"), owner,
                "{\"paidOn\":\"%s\",\"amount\":10000,\"mode\":\"BANK\",\"transactionId\":\"UTR778\"}".formatted(TODAY))
                .andExpect(jsonPath("$.payStatus").value("PARTLY_PAID")).andExpect(jsonPath("$.totals.due").value(4160.0));

        // a customer: the first phone number is unique in the brand
        String customer = idOf(json(post("/api/trade/customers"), owner, """
                {"brandId":%d,"name":"Kumar Residence","phone":"9847022222","address":"Kakkanad, Kochi"}
                """.formatted(dw)).andExpect(status().isCreated()).andExpect(jsonPath("$.code", matchesPattern("DWC-\\d{4}"))));
        json(post("/api/trade/customers"), owner, "{\"brandId\":%d,\"name\":\"Someone\",\"phone\":\"9847022222\"}".formatted(dw))
                .andExpect(status().isConflict());

        // quotation: 3 windows at 2500 and installation; 500 off; 18% GST
        String quote = idOf(json(post("/api/trade/sales"), owner, """
                {"kind":"QUOTATION","brandId":%d,"customerId":%s,"docDate":"%s","validUntil":"%s","gstRate":18,"discount":500,
                 "lines":[{"itemId":%s,"name":"uPVC sliding window","size":"6x4","quantity":3,"rate":2500},
                          {"name":"Installation","quantity":1,"rate":1000}],"terms":"50%% advance"}
                """.formatted(dw, customer, TODAY, TODAY.plusDays(15), item)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number", matchesPattern("DWQTN-\\d{6}-\\d{3}")))
                .andExpect(jsonPath("$.totals.subtotal").value(8500.0))
                .andExpect(jsonPath("$.totals.taxable").value(8000.0))
                .andExpect(jsonPath("$.totals.gstAmount").value(1440.0))
                .andExpect(jsonPath("$.totals.total").value(9440.0))
                .andExpect(jsonPath("$.lines[0].unitCost").value(1200.0)));
        byte[] quotePdf = mockMvc.perform(get("/api/trade/sales/" + quote + "/pdf").header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        // a quotation carries no bank or UPI details
        com.lowagie.text.pdf.PdfReader quoteReader = new com.lowagie.text.pdf.PdfReader(quotePdf);
        String quoteText = new com.lowagie.text.pdf.parser.PdfTextExtractor(quoteReader).getTextFromPage(1);
        org.junit.jupiter.api.Assertions.assertTrue(quoteText.contains("QUOTATION"), quoteText);
        org.junit.jupiter.api.Assertions.assertFalse(quoteText.contains("UPI ID") || quoteText.contains("Bank account") || quoteText.contains("Scan"), quoteText);

        // made into an invoice: a draft, paid in advance, then issued (number, stock out)
        String invoice = idOf(mockMvc.perform(post("/api/trade/sales/" + quote + "/invoice").header("Authorization", auth(owner)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.number").value(nullValue())));
        mockMvc.perform(get("/api/trade/sales/" + quote).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("ACCEPTED")).andExpect(jsonPath("$.invoiceId").value(Integer.parseInt(invoice)));
        pay(invoice, "PAYMENT", "2000").andExpect(jsonPath("$.totals.paid").value(2000.0));
        mockMvc.perform(post("/api/trade/sales/" + invoice + "/issue").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.number", matchesPattern("DWSLD-\\d{6}-\\d{3}")))
                .andExpect(jsonPath("$.payStatus").value("PARTLY_PAID"));
        mockMvc.perform(get("/api/trade/items/" + item).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.item.onHand").value(12.0)).andExpect(jsonPath("$.item.totalOut").value(3.0));
        pay(invoice, "PAYMENT", "7441").andExpect(status().isBadRequest());
        pay(invoice, "PAYMENT", "7440").andExpect(jsonPath("$.payStatus").value("PAID"));

        // reports while it is issued
        mockMvc.perform(get("/api/trade/reports/sales").header("Authorization", auth(owner)).param("brandId", String.valueOf(dw))
                        .param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(jsonPath("$.totals.invoices").value(1))
                .andExpect(jsonPath("$.totals.total").value(9440.0))
                .andExpect(jsonPath("$.byCustomer[0].label").value(startsWith("Kumar Residence")))
                .andExpect(jsonPath("$.byItem[0].amount").value(7500.0))
                .andExpect(jsonPath("$.byItem[0].cost").value(3600.0));
        mockMvc.perform(get("/api/trade/reports/purchases").header("Authorization", auth(owner)).param("brandId", String.valueOf(dw))
                        .param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(jsonPath("$.bySupplier[0].label").value(startsWith("Fenesta Kochi")))
                .andExpect(jsonPath("$.bySupplier[0].amount").value(12000.0))
                .andExpect(jsonPath("$.totals.due").value(4160.0));
        mockMvc.perform(get("/api/trade/reports/owed").header("Authorization", auth(owner)).param("brandId", String.valueOf(dw)))
                .andExpect(jsonPath("$.payable").value(4160.0));
        byte[] xlsx = mockMvc.perform(get("/api/trade/reports/sales/excel").header("Authorization", auth(owner))
                        .param("brandId", String.valueOf(dw)).param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(XlsxRead.sheets(xlsx)).contains("Invoices", "Lines", "By customer", "By product type", "By item");
        mockMvc.perform(get("/api/trade/overview").header("Authorization", auth(owner)).param("brandId", String.valueOf(dw)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.salesThisMonth").value(greaterThanOrEqualTo(9440.0)));
        mockMvc.perform(get("/api/trade/customers/" + customer).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.balance.billed").value(9440.0)).andExpect(jsonPath("$.balance.due").value(0.0))
                .andExpect(jsonPath("$.invoices").value(1)).andExpect(jsonPath("$.quotations").value(1));

        // an issued invoice is not changed; cancelling needs the money refunded first, then stock comes back
        json(put("/api/trade/sales/" + invoice), owner, """
                {"kind":"INVOICE","brandId":%d,"customerId":%s,"docDate":"%s","gstRate":0,"lines":[{"name":"x","quantity":1,"rate":1}]}
                """.formatted(dw, customer, TODAY)).andExpect(status().isConflict());
        json(post("/api/trade/sales/" + invoice + "/cancel"), owner, "{\"reason\":\"Order changed\"}").andExpect(status().isConflict());
        pay(invoice, "REFUND", "9440").andExpect(jsonPath("$.totals.paid").value(0.0));
        json(post("/api/trade/sales/" + invoice + "/cancel"), owner, "{\"reason\":\"Order changed\"}")
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/trade/items/" + item).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.item.onHand").value(15.0)).andExpect(jsonPath("$.moves[0].kind").value("SALE_CANCEL"));

        // not enough stock: the invoice is not issued
        String big = idOf(json(post("/api/trade/sales"), owner, """
                {"kind":"INVOICE","brandId":%d,"customerId":%s,"docDate":"%s","gstRate":18,
                 "lines":[{"itemId":%s,"name":"uPVC sliding window","quantity":100,"rate":2500}]}
                """.formatted(dw, customer, TODAY, item)).andExpect(status().isCreated()));
        mockMvc.perform(post("/api/trade/sales/" + big + "/issue").header("Authorization", auth(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", startsWith("Only 15 piece of UPVC-SW-01")));
        mockMvc.perform(delete("/api/trade/sales/" + big).header("Authorization", auth(owner))).andExpect(status().isNoContent());

        // every step is in the history, with who did it
        mockMvc.perform(get("/api/trade/sales/" + invoice).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.createdByName").value("Amsidel Owner"))
                .andExpect(jsonPath("$.updatedByName").value("Amsidel Owner"))
                .andExpect(jsonPath("$.history[*].action", contains("CANCELLED", "REFUND", "PAYMENT", "ISSUED", "PAYMENT", "CREATED")))
                .andExpect(jsonPath("$.history[0].byName").value("Amsidel Owner"))
                .andExpect(jsonPath("$.history[0].details").value(startsWith("Order changed")));
        mockMvc.perform(get("/api/trade/sales/" + quote).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.history[*].action", contains("INVOICED", "CREATED")));
        json(put("/api/trade/items/" + item), owner, """
                {"typeId":%d,"supplierId":%s,"code":"UPVC-SW-01","name":"uPVC sliding window","sizes":"6x4, 8x7",
                 "supplierPrice":1200,"mrp":3200,"showroomPrice":3000,"minSellPrice":2200,"reorderLevel":3}
                """.formatted(upvc, supplier)).andExpect(status().isOk()).andExpect(jsonPath("$.updatedByName").value("Amsidel Owner"));
        mockMvc.perform(get("/api/trade/items/" + item).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.history[0].action").value("UPDATED"))
                .andExpect(jsonPath("$.history[0].details").value("Showroom price ₹2,800.00 → ₹3,000.00"));
        mockMvc.perform(get("/api/trade/history/CUSTOMER/" + customer).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[0].action").value("CREATED"));
        mockMvc.perform(get("/api/trade/history").header("Authorization", auth(owner)).param("brandId", String.valueOf(dw))
                        .param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(jsonPath("$.length()").value(greaterThan(10)))
                .andExpect(jsonPath("$[*].subjectType", hasItems("INVOICE", "QUOTATION", "PURCHASE", "ITEM", "CUSTOMER", "SUPPLIER")));

        // a payment recorded by mistake is voided, not deleted
        String bview = body(mockMvc.perform(get("/api/trade/purchases/" + bill).header("Authorization", auth(owner))));
        Integer paymentId = JsonPath.read(bview, "$.payments[0].id");
        json(post("/api/trade/payments/" + paymentId + "/void"), owner, "{\"reason\":\"Wrong bill\"}")
                .andExpect(jsonPath("$.totals.paid").value(0.0)).andExpect(jsonPath("$.payments[0].voided").value(true));
    }

    @Test
    void dwHasTheShopsUpiQrOnItsInvoices() throws Exception {
        mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.code == 'DW')].upiQrPayee").value(hasItem("8943504808@axl")))
                .andExpect(jsonPath("$[?(@.code == 'DW')].upiId").value(hasItem("8943504808@axl")));
        mockMvc.perform(get("/api/brands/" + dw + "/upi-qr").header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        // not public, unlike the logo
        mockMvc.perform(get("/api/brands/" + dw + "/upi-qr")).andExpect(status().isUnauthorized());
    }

    @Test
    void stockByHandAndWhoMaySeeWhat() throws Exception {
        String item = idOf(json(post("/api/trade/items"), owner, """
                {"brandId":%d,"typeId":%d,"code":"UPVC-DOOR-9","name":"uPVC door","supplierPrice":5000,"mrp":9000,
                 "showroomPrice":8000,"minSellPrice":7000}
                """.formatted(dw, upvc)).andExpect(status().isCreated()));
        json(post("/api/trade/items/" + item + "/stock"), owner,
                "{\"kind\":\"ADJUST_IN\",\"quantity\":4,\"movedOn\":\"%s\",\"note\":\"Counted\"}".formatted(TODAY))
                .andExpect(jsonPath("$.item.onHand").value(4.0));
        json(post("/api/trade/items/" + item + "/stock"), owner,
                "{\"kind\":\"ADJUST_OUT\",\"quantity\":5,\"movedOn\":\"%s\",\"note\":\"Damaged\"}".formatted(TODAY))
                .andExpect(status().isConflict());
        json(post("/api/trade/items/" + item + "/stock"), owner,
                "{\"kind\":\"SALE\",\"quantity\":1,\"movedOn\":\"%s\",\"note\":\"x\"}".formatted(TODAY))
                .andExpect(status().isBadRequest());
        json(post("/api/trade/items"), owner, """
                {"brandId":%d,"typeId":%d,"code":"upvc-door-9","name":"Copy","supplierPrice":1,"mrp":2,"showroomPrice":2,"minSellPrice":1}
                """.formatted(dw, upvc)).andExpect(status().isConflict());

        // an employee has no product menus by default
        String employee = employee("EMPLOYEE");
        mockMvc.perform(get("/api/trade/items").header("Authorization", auth(employee))).andExpect(status().isForbidden());
        // a brand admin works with their own brand only
        String admin = employee("ADMIN");
        mockMvc.perform(get("/api/trade/brands").header("Authorization", auth(admin)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].code").value("DW"));
        mockMvc.perform(get("/api/trade/items").header("Authorization", auth(admin)))
                .andExpect(jsonPath("$[*].code", hasItem("UPVC-DOOR-9")));
        // the website's public catalogue stays apart from these prices
        mockMvc.perform(get("/api/trade/items")).andExpect(status().isUnauthorized());
    }

    @Test
    void aBrandInAnotherCurrencyPrintsItsSymbolAndWords() throws Exception {
        // a brand set to US dollars: the symbol and the words change, nothing is converted; UPI is left out
        String brand = idOf(json(post("/api/brands"), owner, """
                {"code":"EXP","name":"Amsidel Exports","bankAccount":"50100099887766","ifsc":"HDFC0001234","upi":"exports@okhdfc","currency":"USD"}
                """).andExpect(status().isCreated()).andExpect(jsonPath("$.currency").value("USD")));
        mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.code == 'DW')].currency").value(hasItem("INR")));
        String customer = idOf(json(post("/api/trade/customers"), owner,
                "{\"brandId\":%s,\"name\":\"Global Homes LLC\",\"phone\":\"9847055555\"}".formatted(brand)));
        String invoice = idOf(json(post("/api/trade/sales"), owner, """
                {"kind":"INVOICE","brandId":%s,"customerId":%s,"docDate":"%s","gstRate":0,
                 "lines":[{"name":"Aluminium sliding door","quantity":2,"rate":1234.50}]}
                """.formatted(brand, customer, TODAY)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.company.currency").value("USD")).andExpect(jsonPath("$.company.currencySymbol").value("$")));
        mockMvc.perform(post("/api/trade/sales/" + invoice + "/issue").header("Authorization", auth(owner))).andExpect(status().isOk());
        byte[] pdf = mockMvc.perform(get("/api/trade/sales/" + invoice + "/pdf").header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String text = new com.lowagie.text.pdf.parser.PdfTextExtractor(new com.lowagie.text.pdf.PdfReader(pdf)).getTextFromPage(1).replaceAll("\\s+", " ");
        assertThat(text).contains("$2,469.00").contains("US Dollar Two Thousand Four Hundred Sixty-Nine Only")
                .doesNotContain("₹").doesNotContain("exports@okhdfc").contains("50100099887766");

        // the same invoice as a receipt on a 7, 12 and 15 cm roll: that width, only as long as the bill
        float[] widthsMm = {70, 120, 150};
        String[] sizes = {"70MM", "120MM", "150MM"};
        for (int i = 0; i < sizes.length; i++) {
            byte[] roll = mockMvc.perform(get("/api/trade/sales/" + invoice + "/pdf").param("size", sizes[i]).header("Authorization", auth(owner)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("mm.pdf")))
                    .andReturn().getResponse().getContentAsByteArray();
            com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(roll);
            com.lowagie.text.Rectangle page = reader.getPageSize(1);
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            assertThat(page.getWidth()).isCloseTo(widthsMm[i] * 72f / 25.4f, org.assertj.core.data.Offset.offset(0.5f));
            assertThat(page.getHeight()).isBetween(144f, 1000f);
            String receipt = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1).replaceAll("\\s+", " ");
            assertThat(receipt).contains("Aluminium sliding door").contains("$2,469.00").contains("TOTAL");
        }
        mockMvc.perform(get("/api/trade/sales/" + invoice + "/pdf").param("size", "9CM").header("Authorization", auth(owner)))
                .andExpect(status().isBadRequest());
    }

        // ---- helpers ----

    private ResultActions pay(String docId, String kind, String amount) throws Exception {
        return json(post("/api/trade/sales/" + docId + "/payments"), owner,
                "{\"paidOn\":\"%s\",\"amount\":%s,\"kind\":\"%s\",\"mode\":\"UPI\",\"transactionId\":\"T%s\"}".formatted(TODAY, amount, kind, amount));
    }

    /** A new person of DW with this user type; returns their token. */
    @Test
    void aSalesOrderConfirmsTheProjectThenBecomesTheInvoice() throws Exception {
        String customer = idOf(json(post("/api/trade/customers"), owner,
                "{\"brandId\":%d,\"name\":\"Order Villa\",\"phone\":\"9847077777\"}".formatted(dw)).andExpect(status().isCreated()));
        String project = idOf(json(post("/api/trade/projects"), owner,
                "{\"brandId\":%d,\"name\":\"Villa doors\",\"customerId\":%s,\"startDate\":\"%s\"}".formatted(dw, customer, TODAY)).andExpect(status().isCreated()));
        String item = idOf(json(post("/api/trade/items"), owner, """
                {"brandId":%d,"typeId":%d,"code":"so-door-01","name":"Teak door","supplierPrice":10000,"mrp":20000,
                 "showroomPrice":18000,"minSellPrice":15000,"openingStock":10}
                """.formatted(dw, upvc)).andExpect(status().isCreated()));
        String lines = "[{\"itemId\":%s,\"name\":\"Teak door\",\"quantity\":3,\"rate\":18000},{\"name\":\"Installation\",\"quantity\":1,\"rate\":3000}]".formatted(item);
        String quote = idOf(json(post("/api/trade/sales"), owner, """
                {"kind":"QUOTATION","brandId":%d,"customerId":%s,"projectId":%s,"docDate":"%s","gstRate":18,"lines":%s}
                """.formatted(dw, customer, project, TODAY, lines)).andExpect(status().isCreated()));

        // the quotation becomes a sales order with its own number; the quotation is accepted
        String order = body(mockMvc.perform(post("/api/trade/sales/" + quote + "/order").header("Authorization", auth(owner)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("SALES_ORDER"))
                .andExpect(jsonPath("$.number", matchesPattern("DWSOR-\\d{6}-\\d{3}"))).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.sourceKind").value("QUOTATION")).andExpect(jsonPath("$.lines.length()").value(2)));
        String orderId = String.valueOf((Object) JsonPath.read(order, "$.id"));
        mockMvc.perform(get("/api/trade/sales/" + quote).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("ACCEPTED")).andExpect(jsonPath("$.madeKind").value("SALES_ORDER"));
        mockMvc.perform(post("/api/trade/sales/" + quote + "/order").header("Authorization", auth(owner))).andExpect(status().isConflict());
        // not invoiced before it is confirmed
        mockMvc.perform(post("/api/trade/sales/" + orderId + "/invoice").header("Authorization", auth(owner))).andExpect(status().isConflict());

        // the customer drops the installation and takes 2 doors
        json(put("/api/trade/sales/" + orderId), owner, """
                {"kind":"SALES_ORDER","brandId":%d,"customerId":%s,"projectId":%s,"docDate":"%s","gstRate":18,
                 "lines":[{"itemId":%s,"name":"Teak door","quantity":2,"rate":18000}]}
                """.formatted(dw, customer, project, TODAY, item)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.subtotal").value(36000.0));

        // confirmed: fixed, no stock out, and the project is Confirmed
        mockMvc.perform(post("/api/trade/sales/" + orderId + "/issue").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("ISSUED")).andExpect(jsonPath("$.editable").value(false));
        mockMvc.perform(get("/api/trade/projects/" + project).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.history[0].details", containsString("sales order")));
        mockMvc.perform(get("/api/trade/items/" + item).header("Authorization", auth(owner))).andExpect(jsonPath("$.item.onHand").value(10.0));
        json(put("/api/trade/sales/" + orderId), owner, """
                {"kind":"SALES_ORDER","brandId":%d,"customerId":%s,"docDate":"%s","gstRate":18,"lines":[{"name":"x","quantity":1,"rate":1}]}
                """.formatted(dw, customer, TODAY)).andExpect(status().isConflict());

        // the invoice is made from the order (2 doors), issued: stock out, invoice number of its own
        String invoice = idOf(mockMvc.perform(post("/api/trade/sales/" + orderId + "/invoice").header("Authorization", auth(owner)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("INVOICE"))
                .andExpect(jsonPath("$.sourceKind").value("SALES_ORDER")).andExpect(jsonPath("$.quotationId").value(Integer.parseInt(orderId))));
        mockMvc.perform(post("/api/trade/sales/" + invoice + "/issue").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.number", matchesPattern("DWSLD-\\d{6}-\\d{3}")));
        mockMvc.perform(get("/api/trade/items/" + item).header("Authorization", auth(owner))).andExpect(jsonPath("$.item.onHand").value(8.0));
        // an order with its invoice is not cancelled; sales orders have their own list and PDF
        json(post("/api/trade/sales/" + orderId + "/cancel"), owner, "{\"reason\":\"changed mind\"}").andExpect(status().isConflict());
        mockMvc.perform(get("/api/trade/sales").param("kind", "SALES_ORDER").param("brandId", String.valueOf(dw))
                        .param("from", TODAY.minusDays(1).toString()).param("to", TODAY.plusDays(1).toString()).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.id == " + orderId + ")].status").value(hasItem("ISSUED")));
        mockMvc.perform(get("/api/trade/sales/" + orderId + "/pdf").header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", containsString("Sales-order-DWSOR-")));
    }

    @Test
    void aReturnLowersStockAndWhatCameInNotWhatWentOut() throws Exception {
        String item = idOf(json(post("/api/trade/items"), owner, """
                {"brandId":%d,"typeId":%d,"code":"ret-01","name":"Returned window","supplierPrice":1500,"mrp":4500,
                 "showroomPrice":4000,"minSellPrice":3500,"openingStock":5}
                """.formatted(dw, upvc)).andExpect(status().isCreated()));
        json(post("/api/trade/items/" + item + "/stock"), owner,
                "{\"kind\":\"ADJUST_OUT\",\"quantity\":3,\"movedOn\":\"%s\",\"note\":\"Sold at the counter\"}".formatted(TODAY))
                .andExpect(jsonPath("$.item.totalOut").value(3.0)).andExpect(jsonPath("$.item.onHand").value(2.0));
        // 1 sent back to the supplier: on hand 2 -> 1, total in 5 -> 4, total out (sold) stays 3
        json(post("/api/trade/items/" + item + "/stock"), owner,
                "{\"kind\":\"RETURN_OUT\",\"quantity\":1,\"movedOn\":\"%s\",\"note\":\"Sent back to the supplier, damaged\"}".formatted(TODAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.onHand").value(1.0)).andExpect(jsonPath("$.item.totalIn").value(4.0))
                .andExpect(jsonPath("$.item.totalOut").value(3.0))
                .andExpect(jsonPath("$.moves[0].kind").value("RETURN_OUT")).andExpect(jsonPath("$.moves[0].balanceAfter").value(1.0));
        // a move saved with the old "added back" return still reads; it is not offered any more
        jdbc.update("insert into stock_moves (item_id, moved_on, kind, quantity, balance_after, unit_cost, note, created_at)"
                + " values (?, ?, 'RETURN_IN', 1, 2, 1500, 'Old return', current_timestamp)", Long.valueOf(item), TODAY);
        mockMvc.perform(get("/api/trade/items/" + item).header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.moves[*].kind", hasItem("RETURN_IN")));
        json(post("/api/trade/items/" + item + "/stock"), owner,
                "{\"kind\":\"RETURN_IN\",\"quantity\":1,\"movedOn\":\"%s\",\"note\":\"x\"}".formatted(TODAY)).andExpect(status().isBadRequest());
        // not more than is on hand
        json(post("/api/trade/items/" + item + "/stock"), owner,
                "{\"kind\":\"RETURN_OUT\",\"quantity\":5,\"movedOn\":\"%s\",\"note\":\"Too many\"}".formatted(TODAY))
                .andExpect(status().isConflict());
    }

    @Test
    void qrLabelsAndStockReceivedByScanningThem() throws Exception {
        String item = idOf(json(post("/api/trade/items"), owner, """
                {"brandId":%d,"typeId":%d,"code":"qr-door-01","name":"Steel safety door","sizes":"7x3","hsnCode":"7308",
                 "supplierPrice":9000,"mrp":15000,"showroomPrice":13500,"minSellPrice":12000,"openingStock":2}
                """.formatted(dw, upvc)).andExpect(status().isCreated()));
        // the label holds the item's details, not its stock
        String payload = JsonPath.read(body(mockMvc.perform(get("/api/trade/items/" + item + "/qr").header("Authorization", auth(owner)))), "$.payload");
        assertThat(payload).contains("\"t\":\"AMSIDEL-ITEM\"").contains("\"code\":\"QR-DOOR-01\"").contains("\"showroomPrice\":13500")
                .doesNotContain("onHand");
        byte[] sheet = mockMvc.perform(post("/api/trade/items/qr-labels").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"itemId\":%s,\"copies\":6}],\"showPrice\":true}".formatted(item)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(sheet, 0, 5)).isEqualTo("%PDF-");

        // a label of an item this brand does not have yet (another shop's), and garbage
        String newLabel = payload.replace("QR-DOOR-01", "QR-WIN-77").replace("Steel safety door", "Casement window")
                .replace("\"showroomPrice\":13500", "\"showroomPrice\":4200");
        json(post("/api/trade/stock-receipts"), owner, "{\"brandId\":%d,\"lines\":[{\"payload\":\"hello\",\"quantity\":1}]}".formatted(dw))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("This QR code is not an item label"));

        // scanned: the door three times (two reads of 1 and one of 2 put together), the new window once; someone on Items & stock sends it
        String staffId = idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Store keeper\",\"userId\":\"tr.store\",\"password\":\"ab@123\",\"role\":\"EMPLOYEE\",\"brandId\":%d,\"phone\":\"+91 98470 00000\",\"joinedDate\":\"2026-01-05\"}"
                        .formatted(dw))).andExpect(status().isCreated()));
        // Products & stock (the parent) gives Items & stock but not Approve scanned stock: that is its own tick
        json(put("/api/menu-access/EMPLOYEE/" + staffId), owner, "{\"checkpoints\":[\"11100\"]}").andExpect(status().isOk());
        String staff = token(changePin(token(login("tr.store", "ab@123")), "ab@123", "cd@456"));
        mockMvc.perform(get("/api/auth/access").header("Authorization", auth(staff)))
                .andExpect(jsonPath("$.codes", hasItem("PROD_ITEMS"))).andExpect(jsonPath("$.codes", not(hasItem("PROD_STOCK_APPROVE"))));
        String esc = payload.replace("\"", "\\\"");
        String escNew = newLabel.replace("\"", "\\\"");
        String receipt = body(json(post("/api/trade/stock-receipts"), staff, """
                {"brandId":%d,"note":"Delivery from Tata Steel","lines":[{"payload":"%s","quantity":1},{"payload":"%s","quantity":1},
                 {"payload":"%s","quantity":2}]}""".formatted(dw, esc, escNew, esc)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].quantity").value(3)).andExpect(jsonPath("$.lines[0].exists").value(true))
                .andExpect(jsonPath("$.lines[1].exists").value(false)).andExpect(jsonPath("$.canDecide").value(false)));
        String receiptId = String.valueOf((Object) JsonPath.read(receipt, "$.id"));
        Integer windowLine = JsonPath.read(receipt, "$.lines[1].id");
        // the store keeper cannot approve their own scan; nothing in stock yet
        json(post("/api/trade/stock-receipts/" + receiptId + "/approve"), staff, "{}").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/trade/items/" + item).header("Authorization", auth(owner))).andExpect(jsonPath("$.item.onHand").value(2.0));

        // the approver corrects the window to 4: the door +3, the window made from its label with 4 in stock
        json(post("/api/trade/stock-receipts/" + receiptId + "/approve"), owner,
                "{\"note\":\"Counted\",\"lines\":[{\"id\":%d,\"quantity\":4}]}".formatted(windowLine))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.lines[1].createdItem").value(true)).andExpect(jsonPath("$.lines[1].approvedQuantity").value(4));
        mockMvc.perform(get("/api/trade/items/" + item).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.item.onHand").value(5.0)).andExpect(jsonPath("$.moves[0].note").value(startsWith("Scanned stock receipt SR-")));
        mockMvc.perform(get("/api/trade/items").param("brandId", String.valueOf(dw)).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.code == 'QR-WIN-77')].onHand").value(hasItem(4.0)))
                .andExpect(jsonPath("$[?(@.code == 'QR-WIN-77')].showroomPrice").value(hasItem(4200.0)))
                .andExpect(jsonPath("$[?(@.code == 'QR-WIN-77')].hsnCode").value(hasItem("7308")));
        // decided once
        json(post("/api/trade/stock-receipts/" + receiptId + "/reject"), owner, "{\"note\":\"x\"}").andExpect(status().isConflict());
        mockMvc.perform(get("/api/trade/stock-receipts").param("brandId", String.valueOf(dw)).header("Authorization", auth(staff)))
                .andExpect(jsonPath("$[0].status").value("APPROVED")).andExpect(jsonPath("$[0].decidedByName").exists());
    }

    private String employee(String role) throws Exception {
        String userId = "tr.p" + (++seq);
        mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Trade %d\",\"userId\":\"%s\",\"password\":\"ab@123\",\"role\":\"%s\",\"brandId\":%d,\"phone\":\"+91 98470 00000\",\"joinedDate\":\"2026-01-05\"}"
                        .formatted(seq, userId, role, dw))).andExpect(status().isCreated());
        return token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"));
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
