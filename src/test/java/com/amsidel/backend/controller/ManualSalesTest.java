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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Manual Sales worklog: tasks, the extra details on a work log entry, approval before anything counts, and who sees what. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "test.context=manualsales")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualSalesTest {

    private static final String LOG = "3006";
    private static final String TASKS = "3007";
    private static final String DETAILS = "4007";
    private static final String WORK_LOG_PAGE = "3001";

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private int dw;
    private int other;
    private int seq;
    private LocalDate today;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))), "$[?(@.code == 'DW')].id");
        dw = ids.getFirst();
        other = JsonPath.read(body(json(post("/api/brands"), owner, "{\"code\":\"MS\",\"name\":\"Manual Other\"}")), "$.id");
        today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    /** {id, token}. */
    /** With the three Sales figures ticks, as most of these tests are not about them. */
    private String[] person(int brand, String reportsTo, String... checkpoints) throws Exception {
        if (checkpoints.length == 0) {
            return exactly(brand, reportsTo);
        }
        String[] all = java.util.Arrays.copyOf(checkpoints, checkpoints.length + 3);
        all[checkpoints.length] = "4009";
        all[checkpoints.length + 1] = "4010";
        all[checkpoints.length + 2] = "4011";
        return exactly(brand, reportsTo, all);
    }

    private String[] exactly(int brand, String reportsTo, String... checkpoints) throws Exception {
        String userId = "ms." + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(json(post("/api/employees"), owner, """
                {"name":"Seller %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,"reportsToId":%s,"phone":"9847000000","joinedDate":"2026-01-05"}
                """.formatted(seq, userId, brand, reportsTo)).andExpect(status().isCreated())), "$.id"));
        if (checkpoints.length > 0) {
            json(put("/api/menu-access/EMPLOYEE/" + id), owner, "{\"checkpoints\":[\"" + String.join("\",\"", checkpoints) + "\"]}").andExpect(status().isOk());
        }
        return new String[]{id, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    private String task(String token, int brand, String title, String customer, String project, String agent, String... assignees) throws Exception {
        String res = body(json(post("/api/manual-sales/tasks"), token, """
                {"title":"%s","brandId":%d,"customerName":"%s","projectName":"%s","agentName":"%s","assigneeIds":[%s]}
                """.formatted(title, brand, customer, project, agent, String.join(",", assignees))).andExpect(status().isCreated()));
        return JsonPath.read(res, "$.code");
    }

    private static String sale(String stage, String number, String date, String amount) {
        return ",\"manualSales\":{\"stageCode\":\"" + stage + "\",\"documentNumber\":" + (number == null ? "null" : "\"" + number + "\"")
                + ",\"documentDate\":" + (date == null ? "null" : "\"" + date + "\"") + ",\"amount\":" + amount + "}";
    }

    private ResultActions logEntry(String token, String code, String extra) throws Exception {
        return json(post("/api/worklog/entries"), token, """
                {"workDate":"%s","taskId":"%s","hours":1,"description":"Worked on it"%s}
                """.formatted(today, code, extra));
    }

    private int logged(ResultActions r) throws Exception {
        return JsonPath.read(body(r.andExpect(status().isCreated())), "$.id");
    }

    private ResultActions summary(String token, String... params) throws Exception {
        var request = get("/api/manual-sales/summary").header("Authorization", auth(token));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    private ResultActions details(String token, String... params) throws Exception {
        var request = get("/api/manual-sales/details").header("Authorization", auth(token));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    private void decide(int logId, boolean approve) throws Exception {
        json(post("/api/work-approvals/decide"), owner, "{\"workLogIds\":[" + logId + "],\"approve\":" + approve + "}").andExpect(status().isOk());
    }

    @Test
    void eachPartHasItsOwnTick() throws Exception {
        String[] none = person(dw, "null", WORK_LOG_PAGE);
        mockMvc.perform(get("/api/manual-sales/tasks").header("Authorization", auth(none[1]))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/manual-sales/stages").header("Authorization", auth(none[1]))).andExpect(status().isForbidden());
        summary(none[1]).andExpect(status().isForbidden());
        details(none[1]).andExpect(status().isForbidden());

        // a task given to someone without the tick is not offered, and cannot be logged
        String code = task(owner, dw, "No tick", "C", "P", "A", none[0]);
        assertThat(code).startsWith("MS-");
        mockMvc.perform(get("/api/tasks/mine").header("Authorization", auth(none[1]))).andExpect(jsonPath("$[?(@.code == '" + code + "')]", hasSize(0)));
        logEntry(none[1], code, sale("QUOTATION_ISSUED", "Q-T", today.toString(), "10")).andExpect(status().isForbidden());

        // with it: offered with its details, and marked as manual sales
        String[] seller = person(dw, "null", WORK_LOG_PAGE, LOG);
        String mine = task(owner, dw, "With tick", "Kumar", "Villa", "Ravi", seller[0]);
        mockMvc.perform(get("/api/tasks/mine").header("Authorization", auth(seller[1])))
                .andExpect(jsonPath("$[?(@.code == '" + mine + "')].manualSales", contains(true)))
                .andExpect(jsonPath("$[?(@.code == '" + mine + "')].customerName", contains("Kumar")));
        mockMvc.perform(get("/api/manual-sales/stages").header("Authorization", auth(seller[1]))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'QUOTATION_ISSUED')].kind", contains("QUOTATION")))
                .andExpect(jsonPath("$[?(@.code == 'SALES_ORDER_ISSUED')].kind", contains("SALES_ORDER")))
                .andExpect(jsonPath("$[?(@.code == 'INVOICE_ISSUED')].kind", contains("INVOICE")))
                .andExpect(jsonPath("$[?(@.code == 'IN_PROGRESS')].kind", contains(nullValue())));
        // the log tick alone does not open the task list or the team's figures
        mockMvc.perform(get("/api/manual-sales/tasks").header("Authorization", auth(seller[1]))).andExpect(status().isForbidden());
        summary(seller[1]).andExpect(status().isOk());
        summary(seller[1], "team", "true").andExpect(status().isForbidden());
    }

    @Test
    void anEntryAsksForTheStatusAndThePapersDetails() throws Exception {
        String[] seller = person(dw, "null", WORK_LOG_PAGE, LOG);
        String code = task(owner, dw, "Details", "C", "P", "A", seller[0]);
        logEntry(seller[1], code, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("status")));
        logEntry(seller[1], code, sale("NOT_A_STATUS", null, null, "null")).andExpect(status().isBadRequest());
        logEntry(seller[1], code, sale("INVOICE_ISSUED", null, today.toString(), "100")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("number")));
        logEntry(seller[1], code, sale("SALES_ORDER_ISSUED", "SO-D", null, "100")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("date")));
        logEntry(seller[1], code, sale("QUOTATION_ISSUED", "Q-D", today.toString(), "null")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("amount")));
        logEntry(seller[1], code, sale("QUOTATION_ISSUED", "Q-D", today.toString(), "0")).andExpect(status().isBadRequest());

        // a status with no paper needs nothing more, and any numbers sent are not kept
        logEntry(seller[1], code, sale("IN_PROGRESS", "X-1", today.toString(), "5")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.manualSales.stageName").value("Started")).andExpect(jsonPath("$.manualSales.kind").doesNotExist())
                .andExpect(jsonPath("$.manualSales.documentNumber").doesNotExist()).andExpect(jsonPath("$.manualSales.amount").doesNotExist());

        logEntry(seller[1], code, sale("SALES_ORDER_ISSUED", "SO-D", today.toString(), "2500.50")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.manualSales.kind").value("SALES_ORDER")).andExpect(jsonPath("$.manualSales.documentNumber").value("SO-D"))
                .andExpect(jsonPath("$.manualSales.amount").value(2500.50)).andExpect(jsonPath("$.manualSales.customerName").value("C"));
    }

    @Test
    void severalInvoicesAreFineButTheSamePaperOnlyOnce() throws Exception {
        String[] seller = person(dw, "null", WORK_LOG_PAGE, LOG);
        String code = task(owner, dw, "Invoices", "C", "P", "A", seller[0]);
        logEntry(seller[1], code, sale("INVOICE_ISSUED", "INV-A1", today.toString(), "1000")).andExpect(status().isCreated());
        logEntry(seller[1], code, sale("INVOICE_ISSUED", "INV-A2", today.toString(), "2000")).andExpect(status().isCreated());
        logEntry(seller[1], code, sale("INVOICE_ISSUED", "inv-a1", today.toString(), "1000")).andExpect(status().isConflict());
        // the same number on another kind of paper, or in another brand, is another paper
        logEntry(seller[1], code, sale("QUOTATION_ISSUED", "INV-A1", today.toString(), "1000")).andExpect(status().isCreated());
        String[] elsewhere = person(other, "null", WORK_LOG_PAGE, LOG);
        logEntry(elsewhere[1], task(owner, other, "Other brand", "C", "P", "A", elsewhere[0]), sale("INVOICE_ISSUED", "INV-A1", today.toString(), "1000")).andExpect(status().isCreated());
    }

    @Test
    void itCountsOnlyOnceApprovedAndGoesBackToTheApproverWhenChanged() throws Exception {
        String[] boss = person(dw, "null");
        String[] seller = person(dw, boss[0], WORK_LOG_PAGE, LOG);
        String code = task(owner, dw, "Approval", "C", "P", "A", seller[0]);
        int log = logged(logEntry(seller[1], code, sale("SALES_ORDER_ISSUED", "SO-AP", today.toString(), "5000")));

        // waiting: not counted, and the approver sees what it says
        summary(seller[1]).andExpect(jsonPath("$.currencies[0].months[0].salesOrders.count").value(0));
        mockMvc.perform(get("/api/work-approvals").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.workLogs[?(@.id == " + log + ")].manualSales.documentNumber", contains("SO-AP")));

        decide(log, true);
        summary(seller[1]).andExpect(jsonPath("$.currencies[0].symbol").value("₹"))
                .andExpect(jsonPath("$.currencies[0].weeks[0].salesOrders.count").value(1))
                .andExpect(jsonPath("$.currencies[0].months[0].salesOrders.amount").value(5000.0))
                .andExpect(jsonPath("$.currencies[0].years[0].salesOrders.amount").value(5000.0))
                .andExpect(jsonPath("$.currencies[0].weeks", hasSize(8))).andExpect(jsonPath("$.currencies[0].months", hasSize(12))).andExpect(jsonPath("$.currencies[0].years", hasSize(3)));

        // changed: back with the approver, and out of the figures until decided again
        json(put("/api/worklog/entries/" + log), seller[1], """
                {"workDate":"%s","taskId":"%s","hours":1,"description":"Corrected"%s}
                """.formatted(today, code, sale("SALES_ORDER_ISSUED", "SO-AP", today.toString(), "6000"))).andExpect(status().isOk());
        summary(seller[1]).andExpect(jsonPath("$.currencies[0].months[0].salesOrders.count").value(0));

        // rejected: never counted, and it gives its number back
        decide(log, false);
        summary(seller[1]).andExpect(jsonPath("$.currencies[0].months[0].salesOrders.count").value(0));
        int again = logged(logEntry(seller[1], code, sale("SALES_ORDER_ISSUED", "SO-AP", today.toString(), "7000")));
        decide(again, true);
        summary(seller[1]).andExpect(jsonPath("$.currencies[0].months[0].salesOrders.amount").value(7000.0));

        // removed from the work log: gone from the figures too
        mockMvc.perform(delete("/api/worklog/entries/" + again).header("Authorization", auth(seller[1]))).andExpect(status().isNoContent());
        summary(seller[1]).andExpect(jsonPath("$.currencies[0].months[0].salesOrders.count").value(0));
    }

    @Test
    void theDetailsFilterAndShowOnlyWhoIMaySee() throws Exception {
        String[] boss = person(dw, "null", DETAILS);
        String[] a = person(dw, boss[0], WORK_LOG_PAGE, LOG);
        String[] b = person(dw, boss[0], WORK_LOG_PAGE, LOG);
        String[] outsider = person(dw, "null", WORK_LOG_PAGE, LOG);
        String[] elsewhere = person(other, "null", WORK_LOG_PAGE, LOG);
        String ta = task(owner, dw, "A", "Filter Cust", "Filter Proj", "Filter Agent", a[0]);
        String tb = task(owner, dw, "B", "Second Cust", "Second Proj", "Second Agent", b[0]);
        decide(logged(logEntry(a[1], ta, sale("SALES_ORDER_ISSUED", "F-SO", today.toString(), "100"))), true);
        decide(logged(logEntry(a[1], ta, sale("INVOICE_ISSUED", "F-INV", today.toString(), "40"))), true);
        decide(logged(logEntry(b[1], tb, sale("QUOTATION_ISSUED", "F-Q", today.toString(), "9"))), true);
        int waiting = logged(logEntry(b[1], tb, sale("SALES_ORDER_ISSUED", "F-WAIT", today.toString(), "77")));
        logged(logEntry(outsider[1], task(owner, dw, "O", "Filter Cust", "P", "A", outsider[0]), sale("SALES_ORDER_ISSUED", "F-OUT", today.toString(), "1")));

        // the manager: their two people, not the outsider
        details(boss[1]).andExpect(status().isOk()).andExpect(jsonPath("$.rows", hasSize(4)))
                .andExpect(jsonPath("$.rows[*].documentNumber", not(hasItem("F-OUT"))))
                // the totals are approved only: F-WAIT is listed but does not add up
                .andExpect(jsonPath("$.totals[0].salesOrders.amount").value(100.0)).andExpect(jsonPath("$.totals[0].invoices.amount").value(40.0)).andExpect(jsonPath("$.totals[0].quotations.count").value(1));
        details(boss[1], "employeeId", outsider[0]).andExpect(status().isForbidden());
        details(boss[1], "employeeId", a[0]).andExpect(jsonPath("$.rows", hasSize(2)));
        details(boss[1], "customer", "filter").andExpect(jsonPath("$.rows", hasSize(2)));
        details(boss[1], "project", "SECOND").andExpect(jsonPath("$.rows", hasSize(2)));
        details(boss[1], "agent", "Filter Agent").andExpect(jsonPath("$.rows", hasSize(2)));
        details(boss[1], "kind", "INVOICE").andExpect(jsonPath("$.rows[*].documentNumber", contains("F-INV")));
        details(boss[1], "stageCode", "QUOTATION_ISSUED").andExpect(jsonPath("$.rows[*].documentNumber", contains("F-Q")));
        details(boss[1], "approval", "PENDING").andExpect(jsonPath("$.rows[*].documentNumber", contains("F-WAIT")));
        details(boss[1], "brandId", String.valueOf(other)).andExpect(jsonPath("$.rows", hasSize(0)));
        details(boss[1], "from", today.plusDays(1).toString(), "to", today.plusDays(2).toString()).andExpect(jsonPath("$.rows", hasSize(0)));
        details(boss[1], "from", today.toString(), "to", today.minusDays(1).toString()).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/manual-sales/details/options").header("Authorization", auth(boss[1]))).andExpect(status().isOk())
                .andExpect(jsonPath("$.customers", hasItems("Filter Cust", "Second Cust"))).andExpect(jsonPath("$.people[*].id", not(hasItem(Integer.valueOf(outsider[0])))));

        // the team's figures on the Overview are the same people, without the waiting one
        summary(boss[1], "team", "true").andExpect(jsonPath("$.currencies[0].months[0].salesOrders.amount").value(100.0))
                .andExpect(jsonPath("$.currencies[0].months[0].invoices.amount").value(40.0));
        // someone else's figures are never mine: the outsider has no approver, so theirs counted at once, and only theirs
        summary(outsider[1]).andExpect(jsonPath("$.currencies[0].months[0].salesOrders.amount").value(1.0));
        summary(elsewhere[1]).andExpect(jsonPath("$.currencies[0].months[0].salesOrders.count").value(0));
        // the owner sees everyone
        details(owner, "customer", "Filter Cust").andExpect(jsonPath("$.rows[*].documentNumber", hasItems("F-OUT", "F-SO", "F-INV")));
        assertThat(waiting).isPositive();
    }

    @Test
    void aTasksFiguresShowWhatIsStillToBeInvoiced() throws Exception {
        String[] seller = person(dw, "null", WORK_LOG_PAGE, LOG);
        String[] manager = person(dw, "null", TASKS);
        String code = task(manager[1], dw, "Balance", "Cust", "Proj", "Agent", seller[0]);
        logEntry(seller[1], code, sale("SALES_ORDER_ISSUED", "B-SO", today.toString(), "1000")).andExpect(status().isCreated());
        logEntry(seller[1], code, sale("INVOICE_ISSUED", "B-INV1", today.toString(), "300")).andExpect(status().isCreated());
        logEntry(seller[1], code, sale("INVOICE_ISSUED", "B-INV2", today.toString(), "200")).andExpect(status().isCreated());
        String row = "$[?(@.code == '" + code + "')]";
        mockMvc.perform(get("/api/manual-sales/tasks").header("Authorization", auth(manager[1]))).andExpect(status().isOk())
                .andExpect(jsonPath(row + ".ordered", contains(1000.0))).andExpect(jsonPath(row + ".invoiced", contains(500.0))).andExpect(jsonPath(row + ".balance", contains(500.0)))
                .andExpect(jsonPath(row + ".latestStage", contains("Invoice issued"))).andExpect(jsonPath(row + ".customerName", contains("Cust")));
        // another brand's manager does not see it, and cannot take it
        String[] rival = person(other, "null", TASKS);
        mockMvc.perform(get("/api/manual-sales/tasks").header("Authorization", auth(rival[1]))).andExpect(jsonPath(row, hasSize(0)));
        json(post("/api/manual-sales/tasks"), rival[1], "{\"title\":\"Mine\",\"brandId\":" + dw + ",\"assigneeIds\":[]}").andExpect(status().isForbidden());
    }

    @Test
    void eachColumnHasItsOwnTickAndWhatIsNotTickedIsNotSent() throws Exception {
        String[] seller = person(other, "null", WORK_LOG_PAGE, LOG);
        String code = task(owner, other, "Columns", "Col Cust", "Col Proj", "Col Agent", seller[0]);
        logEntry(seller[1], code, sale("QUOTATION_ISSUED", "CO-Q", today.toString(), "11")).andExpect(status().isCreated());
        logEntry(seller[1], code, sale("SALES_ORDER_ISSUED", "CO-SO", today.toString(), "22")).andExpect(status().isCreated());
        logEntry(seller[1], code, sale("INVOICE_ISSUED", "CO-INV", today.toString(), "33")).andExpect(status().isCreated());

        // quotations and sales orders, no invoices: details, tasks and team figures alike
        String[] noInvoices = exactly(other, "null", DETAILS, TASKS, "4009", "4010");
        details(noInvoices[1], "employeeId", seller[0]).andExpect(status().isForbidden());
        String row = "$[?(@.code == '" + code + "')]";
        listTasks(noInvoices[1], "code", code).andExpect(jsonPath(row + ".quoted", contains(11.0))).andExpect(jsonPath(row + ".ordered", contains(22.0)))
                .andExpect(jsonPath(row + ".invoiced", contains(nullValue()))).andExpect(jsonPath(row + ".balance", contains(nullValue())))
                .andExpect(jsonPath(row + ".latestStage", contains("Sales order issued")));

        // the owner sees them all, and the file has the column
        details(owner, "employeeId", seller[0]).andExpect(jsonPath("$.rows", hasSize(3))).andExpect(jsonPath("$.totals[0].invoices.amount").value(33.0));
        var file = mockMvc.perform(get("/api/manual-sales/tasks/export").header("Authorization", auth(noInvoices[1])).param("code", code)).andExpect(status().isOk()).andReturn().getResponse();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file.getContentAsByteArray()))) {
            List<String> heads = new java.util.ArrayList<>();
            book.getSheet("Tasks").getRow(3).forEach(h -> heads.add(h.getStringCellValue()));
            assertThat(heads).contains("Quoted", "Ordered").doesNotContain("Invoiced", "To invoice");
            List<String> numbers = new java.util.ArrayList<>();
            var entries = book.getSheet("Entries");
            int number = columnOf(entries.getRow(3), "Number");
            for (int i = 4; i <= entries.getLastRowNum(); i++) {
                numbers.add(entries.getRow(i).getCell(number).getStringCellValue());
            }
            assertThat(numbers).containsExactlyInAnyOrder("CO-Q", "CO-SO");
        }

        // their own figures follow their ticks too, whatever they logged
        String[] mine = exactly(other, "null", WORK_LOG_PAGE, LOG, "4009");
        String myTask = task(owner, other, "Mine", "C", "P", "A", mine[0]);
        logEntry(mine[1], myTask, sale("INVOICE_ISSUED", "CO-MINE", today.toString(), "44")).andExpect(status().isCreated())
                // what they typed themselves still comes back to them in their work log
                .andExpect(jsonPath("$.manualSales.documentNumber").value("CO-MINE"));
        summary(mine[1]).andExpect(jsonPath("$.currencies[0].months[0].quotations.count").value(0))
                .andExpect(jsonPath("$.currencies[0].months[0].invoices").doesNotExist()).andExpect(jsonPath("$.currencies[0].months[0].salesOrders").doesNotExist());

        // with the details tick and no invoices: their rows and the Invoice issued status are left out
        String[] viewer = exactly(other, "null", DETAILS, "4009", "4010");
        details(viewer[1], "kind", "INVOICE").andExpect(jsonPath("$.rows", hasSize(0)));
        mockMvc.perform(get("/api/manual-sales/details/options").header("Authorization", auth(viewer[1])))
                .andExpect(jsonPath("$.stages[?(@.code == 'INVOICE_ISSUED')]", hasSize(0))).andExpect(jsonPath("$.stages[?(@.code == 'QUOTATION_ISSUED')]", hasSize(1)));
        // only these three are given by ticking them: Overview's section or the rest give none
        String[] noColumns = exactly(other, "null", "400", "402");
        summary(noColumns[1]).andExpect(status().isOk()).andExpect(jsonPath("$.currencies[0].months[0].quotations").doesNotExist());
    }

    @Test
    void aGoalsLinesFollowTheViewersTicks() throws Exception {
        String[] boss = exactly(other, "null", "6002", "6000", "4010");
        String[] seller = person(other, boss[0], WORK_LOG_PAGE, LOG);
        String review = "/api/performance/cycles/" + cycle() + "/employees/" + seller[0];
        int id = JsonPath.read(body(json(post(review + "/goals"), owner, """
                {"title":"Lines","type":"MANUAL_SALES","targetAmount":1000,"weightage":20,"startDate":"%s","endDate":"%s","tasks":[]}
                """.formatted(today.minusDays(5), today.plusDays(5))).andExpect(status().isCreated())), "$.id");
        String code = task(owner, other, "Goal lines", "C", "P", "A", seller[0]);
        decide(logged(logEntry(seller[1], code, sale("SALES_ORDER_ISSUED", "GL-SO", today.toString(), "400"))), true);
        decide(logged(logEntry(seller[1], code, sale("INVOICE_ISSUED", "GL-INV", today.toString(), "100"))), true);
        String path = "$.goals[?(@.id == " + id + ")]";

        mockMvc.perform(get(review).header("Authorization", auth(owner))).andExpect(jsonPath(path + ".manualLines[*].documentNumber", containsInAnyOrder("GL-SO", "GL-INV")));
        // their manager has the sales orders tick and not invoices: the target's figure and the order show, the invoice does not
        mockMvc.perform(get(review).header("Authorization", auth(boss[1]))).andExpect(status().isOk())
                .andExpect(jsonPath(path + ".achievedAmount", contains(400.0))).andExpect(jsonPath(path + ".manualLines[*].documentNumber", contains("GL-SO")));
    }

    private ResultActions listTasks(String token, String... params) throws Exception {
        var request = get("/api/manual-sales/tasks").header("Authorization", auth(token));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    private ResultActions logOn(String token, String code, LocalDate day, String extra) throws Exception {
        return json(post("/api/worklog/entries"), token, """
                {"workDate":"%s","taskId":"%s","hours":1,"description":"Worked on it"%s}
                """.formatted(day, code, extra));
    }

    @Test
    void theTasksFilterByDatesMonthTaskAndEmployeeAndTheAmountsFollow() throws Exception {
        String[] a = person(other, "null", WORK_LOG_PAGE, LOG);
        String[] b = person(other, "null", WORK_LOG_PAGE, LOG);
        String ta = task(owner, other, "Filter A", "Cust A", "Proj A", "Agent A", a[0], b[0]);
        String tb = task(owner, other, "Filter B", "Cust B", "Proj B", "Agent B", b[0]);
        LocalDate old = today.minusMonths(2).withDayOfMonth(10);
        logOn(a[1], ta, old, sale("SALES_ORDER_ISSUED", "TF-OLD", old.toString(), "1000")).andExpect(status().isCreated());
        logOn(a[1], ta, today, sale("SALES_ORDER_ISSUED", "TF-NEW", today.toString(), "200")).andExpect(status().isCreated());
        logOn(b[1], ta, today, sale("INVOICE_ISSUED", "TF-INV", today.toString(), "50")).andExpect(status().isCreated());
        logOn(b[1], tb, old, sale("QUOTATION_ISSUED", "TF-Q", old.toString(), "9")).andExpect(status().isCreated());
        String rowA = "$[?(@.code == '" + ta + "')]";
        String rowB = "$[?(@.code == '" + tb + "')]";

        // no filter: everything
        listTasks(owner).andExpect(jsonPath(rowA + ".ordered", contains(1200.0))).andExpect(jsonPath(rowA + ".invoiced", contains(50.0))).andExpect(jsonPath(rowA + ".balance", contains(1150.0)));

        // the month two months back: only that month's amounts; both tasks were worked on then
        String from = old.withDayOfMonth(1).toString();
        String to = old.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth()).toString();
        listTasks(owner, "from", from, "to", to).andExpect(jsonPath(rowA + ".ordered", contains(1000.0))).andExpect(jsonPath(rowA + ".invoiced", contains(0))).andExpect(jsonPath(rowA + ".balance", contains(nullValue()))).andExpect(jsonPath(rowB + ".quoted", contains(9.0)));

        // a day with no work on B, and which it was not created on, leaves it out (it was created today, so ask for yesterday)
        LocalDate yesterday = today.minusDays(1);
        listTasks(owner, "from", yesterday.toString(), "to", yesterday.toString()).andExpect(jsonPath(rowA, hasSize(0))).andExpect(jsonPath(rowB, hasSize(0)));
        // created today, though nothing else: still there, at nothing
        String idle = task(owner, other, "Idle", "C", "P", "A", a[0]);
        listTasks(owner, "from", today.toString(), "to", today.toString()).andExpect(jsonPath("$[?(@.code == '" + idle + "')].ordered", contains(0))).andExpect(jsonPath(rowA + ".ordered", contains(200.0)));

        // one task
        listTasks(owner, "code", ta.toLowerCase()).andExpect(jsonPath("$[*].code", contains(ta)));
        // one employee: given to them or logged by them, and only their amounts
        listTasks(owner, "employeeId", a[0]).andExpect(jsonPath(rowB, hasSize(0))).andExpect(jsonPath(rowA + ".ordered", contains(1200.0))).andExpect(jsonPath(rowA + ".invoiced", contains(0)));
        listTasks(owner, "employeeId", b[0]).andExpect(jsonPath(rowA + ".invoiced", contains(50.0))).andExpect(jsonPath(rowA + ".ordered", contains(0)));
        // together, with the search and the status
        listTasks(owner, "q", "cust a", "employeeId", b[0], "status", "OPEN").andExpect(jsonPath("$[*].code", contains(ta)));
        listTasks(owner, "status", "CLOSED", "code", ta).andExpect(jsonPath("$", hasSize(0)));
        listTasks(owner, "from", today.toString(), "to", yesterday.toString()).andExpect(status().isBadRequest());
    }

    @Test
    void theExportFollowsTheFiltersAndListsEachEntry() throws Exception {
        String[] a = person(other, "null", WORK_LOG_PAGE, LOG);
        String[] mgr = person(dw, "null", TASKS);
        String code = task(owner, other, "Export", "Exp Cust", "Exp Proj", "Exp Agent", a[0]);
        LocalDate old = today.minusMonths(3).withDayOfMonth(5);
        logOn(a[1], code, old, sale("SALES_ORDER_ISSUED", "EX-OLD", old.toString(), "700")).andExpect(status().isCreated());
        logOn(a[1], code, today, sale("INVOICE_ISSUED", "EX-NEW", today.toString(), "300")).andExpect(status().isCreated());

        var res = mockMvc.perform(get("/api/manual-sales/tasks/export").header("Authorization", auth(owner)).param("code", code)
                .param("from", old.minusDays(1).toString()).param("to", old.plusDays(1).toString())).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(res.getContentType()).contains("spreadsheetml");
        assertThat(res.getHeader("Content-Disposition")).contains("ManualSalesTasks_" + old.minusDays(1));
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(res.getContentAsByteArray()))) {
            var tasks = book.getSheet("Tasks");
            var header = tasks.getRow(3);
            assertThat(tasks.getRow(4).getCell(0).getStringCellValue()).isEqualTo(code);
            assertThat(tasks.getRow(4).getCell(4).getStringCellValue()).isEqualTo("Exp Cust");
            assertThat(tasks.getRow(4).getCell(columnOf(header, "Ordered")).getNumericCellValue()).isEqualTo(700.0);
            assertThat(tasks.getRow(4).getCell(columnOf(header, "Invoiced")).getNumericCellValue()).isZero();
            assertThat((Object) tasks.getRow(5)).isNull();
            var rows = book.getSheet("Entries");
            var head = rows.getRow(3);
            assertThat(rows.getRow(4).getCell(columnOf(head, "Number")).getStringCellValue()).isEqualTo("EX-OLD");
            assertThat(rows.getRow(4).getCell(columnOf(head, "Approval")).getStringCellValue()).isEqualTo("Approved");
            assertThat((Object) rows.getRow(5)).isNull();
        }

        // no tick, no file; another brand's manager gets a file without it
        mockMvc.perform(get("/api/manual-sales/tasks/export").header("Authorization", auth(a[1]))).andExpect(status().isForbidden());
        var theirs = mockMvc.perform(get("/api/manual-sales/tasks/export").header("Authorization", auth(mgr[1])).param("code", code)).andExpect(status().isOk()).andReturn().getResponse();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(theirs.getContentAsByteArray()))) {
            assertThat((Object) book.getSheet("Tasks").getRow(4)).isNull();
        }
    }

    private static int columnOf(org.apache.poi.ss.usermodel.Row header, String name) {
        for (var c : header) {
            if (name.equals(c.getStringCellValue())) {
                return c.getColumnIndex();
            }
        }
        throw new AssertionError("No column " + name);
    }

    @Test
    void theExistingFlowsStayAsTheyWere() throws Exception {
        String[] seller = person(dw, "null", WORK_LOG_PAGE, LOG);
        String code = task(owner, dw, "Apart", "C", "P", "A", seller[0]);

        // not on the Tasks page, and it cannot be changed from there
        mockMvc.perform(get("/api/tasks").header("Authorization", auth(owner))).andExpect(jsonPath("$[?(@.code == '" + code + "')]", hasSize(0)));
        List<Integer> ids = JsonPath.read(body(mockMvc.perform(get("/api/manual-sales/tasks").header("Authorization", auth(owner)))), "$[?(@.code == '" + code + "')].id");
        json(put("/api/tasks/" + ids.getFirst()), owner, "{\"title\":\"Taken over\",\"brandId\":" + dw + ",\"assigneeIds\":[]}").andExpect(status().isBadRequest());

        // an ordinary task's entry asks for nothing more, and has no sales details
        String plain = JsonPath.read(body(json(post("/api/tasks"), owner, "{\"title\":\"Plain\",\"brandId\":" + dw + ",\"assigneeIds\":[" + seller[0] + "]}").andExpect(status().isCreated())), "$.code");
        assertThat(plain).startsWith("TSK-");
        logEntry(seller[1], plain, "").andExpect(status().isCreated()).andExpect(jsonPath("$.manualSales").doesNotExist());

        // a Task worklog goal has the ordinary task as a sub-task, never the Manual Sales one
        json(post("/api/performance/cycles/" + cycle() + "/employees/" + seller[0] + "/goals"), owner, """
                {"title":"Task worklog","type":"TASK_WORKLOG","weightage":10,"startDate":"%s","endDate":"%s","tasks":[]}
                """.formatted(today.minusDays(5), today.plusDays(5))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tasks[*].worklog.code", hasItem(plain))).andExpect(jsonPath("$.tasks[*].worklog.code", not(hasItem(code))));

        // the new statuses are in the project status list too, between the ones that were there
        List<String> stages = JsonPath.read(body(mockMvc.perform(get("/api/manual-sales/stages").header("Authorization", auth(seller[1])))), "$[*].code");
        assertThat(stages).containsSubsequence("QUEUED", "QUOTATION_ISSUED", "SALES_ORDER_ISSUED", "IN_PROGRESS", "INVOICE_ISSUED", "COMPLETED", "CANCELLED");
    }

    private Integer cycleId;

    private int cycle() throws Exception {
        if (cycleId == null) {
            cycleId = JsonPath.read(body(json(post("/api/performance/cycles"), owner, """
                    {"name":"Manual sales test","startDate":"%s","endDate":"%s"}""".formatted(today.minusMonths(6), today.plusMonths(6))).andExpect(status().isCreated())), "$.id");
        }
        return cycleId;
    }

    @Test
    void aGoalIsReachedByApprovedSalesOrders() throws Exception {
        String[] boss = person(dw, "null");
        String[] seller = person(dw, boss[0], WORK_LOG_PAGE, LOG, "6001");
        String goals = "/api/performance/cycles/" + cycle() + "/employees/" + seller[0] + "/goals";
        String goal = """
                {"title":"Manual sales","type":"MANUAL_SALES","targetAmount":1000,"weightage":50,"startDate":"%s","endDate":"%s","tasks":[]}
                """.formatted(today.minusDays(10), today.plusDays(10));

        // a manager needs the goal type's tick
        json(post(goals), boss[1], goal).andExpect(status().isForbidden());
        json(post(goals), owner, goal.replace("\"targetAmount\":1000,", "")).andExpect(status().isBadRequest());
        String withTask = goal.replace("\"tasks\":[]", "\"tasks\":[{\"title\":\"x\",\"weightage\":100,\"startDate\":\"" + today + "\",\"endDate\":\"" + today + "\"}]");
        json(post(goals), owner, withTask).andExpect(status().isBadRequest());
        int id = JsonPath.read(body(json(post(goals), owner, goal).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("MANUAL_SALES")).andExpect(jsonPath("$.achievedAmount").value(0)).andExpect(jsonPath("$.selfRating").doesNotExist())), "$.id");

        String code = task(owner, dw, "Goal", "Cust", "Proj", "Agent", seller[0]);
        int order = logged(logEntry(seller[1], code, sale("SALES_ORDER_ISSUED", "G-SO", today.toString(), "500")));
        decide(logged(logEntry(seller[1], code, sale("INVOICE_ISSUED", "G-INV", today.toString(), "200"))), true);
        String path = "$.goals[?(@.id == " + id + ")]";
        String review = "/api/performance/cycles/" + cycle() + "/employees/" + seller[0];

        // waiting: nothing yet; invoices do not reach the target, they only show
        mockMvc.perform(get(review).header("Authorization", auth(owner))).andExpect(status().isOk())
                .andExpect(jsonPath(path + ".achievedAmount", contains(0))).andExpect(jsonPath(path + ".manualLines[*].documentNumber", contains("G-INV")));

        decide(order, true);
        mockMvc.perform(get(review).header("Authorization", auth(owner)))
                .andExpect(jsonPath(path + ".achievedAmount", contains(500.0))).andExpect(jsonPath(path + ".achievedPercent", contains(50.0)))
                .andExpect(jsonPath(path + ".progress", contains(50.0))).andExpect(jsonPath(path + ".selfRating", contains(2.5)))
                .andExpect(jsonPath(path + ".manualLines[*].documentNumber", hasItems("G-SO", "G-INV")))
                .andExpect(jsonPath(path + ".manualLines[?(@.documentNumber == 'G-SO')].customerName", contains("Cust")));

        // the person cannot rate it or move it by hand
        json(put("/api/performance/goals/" + id + "/progress"), seller[1], "{\"progress\":90}").andExpect(status().isBadRequest());
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String token, String content) throws Exception {
        return mockMvc.perform(request.header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private ResultActions login(String userId, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\",\"password\":\"%s\"}".formatted(userId, pin)));
    }

    private ResultActions changePin(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/auth/change-password").header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
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
