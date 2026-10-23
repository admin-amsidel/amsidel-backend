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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Monthly payslips: drafts made from the salary, published to the employee, with year to date and a PDF. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=payslips", "app.payslip.company-address=Kakkanad, Kochi, Kerala"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayslipTest {

    @Autowired
    private MockMvc mockMvc;

    private String owner;
    private long dw;
    private Map<String, Integer> components;
    private int seq;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.get(0);
        List<Map<String, Object>> all = JsonPath.read(body(mockMvc.perform(get("/api/payroll/components")
                .header("Authorization", auth(owner)))), "$");
        components = all.stream().collect(Collectors.toMap(c -> (String) c.get("code"), c -> (Integer) c.get("id")));
    }

    @Test
    void draftsArePrivateUntilPublished() throws Exception {
        String[] emp = employee("2025-09-09");
        salary(emp[0], "2025-09-09");

        String result = body(json(post("/api/payroll/payslips/generate"), owner, "{\"year\":2025,\"month\":9,\"employeeIds\":[" + emp[0] + "]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1)));
        String id = payslipId(emp[0], 2025, 9);
        // joined Tue 9th: 19 working days (not Sundays) of 26; earnings = monthly / 26 x 19, deductions and reimbursements in full
        mockMvc.perform(get("/api/payroll/payslips/" + id).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.periodLabel").value("September 2025"))
                .andExpect(jsonPath("$.daysInMonth").value(30))
                .andExpect(jsonPath("$.workingDays").value(26))
                .andExpect(jsonPath("$.paidDays").value(19))
                .andExpect(jsonPath("$.lines[?(@.code == 'BASIC')].amount").value(hasItem(71453.00)))
                .andExpect(jsonPath("$.lines[?(@.code == 'BASIC')].fullAmount").value(hasItem(97778.00)))
                .andExpect(jsonPath("$.lines[?(@.code == 'TDS')].amount").value(hasItem(24957.00)))
                .andExpect(jsonPath("$.company.address").value("Kakkanad, Kochi, Kerala"));
        // generating again makes nothing new
        json(post("/api/payroll/payslips/generate"), owner, "{\"year\":2025,\"month\":9,\"employeeIds\":[" + emp[0] + "]}")
                .andExpect(jsonPath("$.created").value(0));

        // the employee does not see the draft
        mockMvc.perform(get("/api/my-pay").param("fy", "2025").header("Authorization", auth(emp[1])))
                .andExpect(status().isOk()).andExpect(jsonPath("$.payslips.length()").value(0));
        mockMvc.perform(get("/api/my-pay/payslips/" + id).header("Authorization", auth(emp[1]))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/payroll/payslips").param("year", "2025").param("month", "9").header("Authorization", auth(emp[1])))
                .andExpect(status().isForbidden());

        // payroll puts the full month back (the payslip's figures) and saves as a draft, then publishes
        String lines = fullMonthLines(id);
        json(put("/api/payroll/payslips/" + id), owner, "{\"payDate\":\"2025-09-30\",\"lopDays\":0,\"lines\":" + lines + ",\"publish\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.money.net").value(214945.00))
                .andExpect(jsonPath("$.netInWords").value("Indian Rupee Two Lakh Fourteen Thousand Nine Hundred Forty-Five Only"));
        // crediting a draft is refused
        json(put("/api/payroll/payslips/" + id + "/payment"), owner, "{\"paidOn\":\"2025-09-30\",\"mode\":\"BANK\"}")
                .andExpect(status().isConflict());
        json(put("/api/payroll/payslips/" + id), owner, "{\"payDate\":\"2025-09-30\",\"lopDays\":0,\"lines\":" + lines + ",\"publish\":true}")
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.paidOn").value(nullValue()));
        // published, not credited yet: still hidden from the employee
        mockMvc.perform(get("/api/my-pay/payslips/" + id).header("Authorization", auth(emp[1]))).andExpect(status().isNotFound());
        json(put("/api/payroll/payslips/" + id + "/payment"), owner, "{\"paidOn\":\"2099-01-01\",\"mode\":\"BANK\"}")
                .andExpect(status().isBadRequest());
        json(put("/api/payroll/payslips/" + id + "/payment"), owner,
                "{\"paidOn\":\"2025-10-01\",\"mode\":\"BANK\",\"reference\":\"UTR123456789\",\"remarks\":\" Paid with the October batch \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidOn").value("2025-10-01"))
                .andExpect(jsonPath("$.paymentMode").value("BANK"))
                .andExpect(jsonPath("$.paymentRef").value("UTR123456789"))
                .andExpect(jsonPath("$.paymentRemarks").value("Paid with the October batch"));
        mockMvc.perform(get("/api/payroll/payslips").param("year", "2025").param("month", "9").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.rows[?(@.employee.id == " + emp[0] + ")].paymentMode").value(hasItem("BANK")))
                .andExpect(jsonPath("$.credited").value(greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/my-pay/payslips/" + id).header("Authorization", auth(emp[1])))
                .andExpect(status().isOk()).andExpect(jsonPath("$.money.net").value(214945.00))
                .andExpect(jsonPath("$.paymentRef").value("UTR123456789"))
                // payroll's remarks stay with payroll
                .andExpect(jsonPath("$.paymentRemarks").value(nullValue()))
                .andExpect(jsonPath("$.editable").value(false));
        byte[] pdf = mockMvc.perform(get("/api/my-pay/payslips/" + id + "/pdf").header("Authorization", auth(emp[1])))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("Payslip-2025-09-")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(5000);

        // published: locked until moved back to draft
        json(put("/api/payroll/payslips/" + id), owner, "{\"payDate\":\"2025-09-30\",\"lopDays\":1,\"lines\":[],\"publish\":false}")
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/payroll/payslips/" + id).header("Authorization", auth(owner))).andExpect(status().isConflict());
        // credited: cannot go back to draft until the credit is undone
        mockMvc.perform(post("/api/payroll/payslips/" + id + "/unpublish").header("Authorization", auth(owner)))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/payroll/payslips/" + id + "/payment").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.paidOn").value(nullValue()));
        mockMvc.perform(get("/api/my-pay/payslips/" + id).header("Authorization", auth(emp[1]))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/payroll/payslips/" + id + "/unpublish").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void yearToDateAndTheEmployeesDashboard() throws Exception {
        String[] emp = employee("2025-04-01");
        salary(emp[0], "2025-04-01");
        json(post("/api/payroll/payslips/generate"), owner, "{\"year\":2025,\"month\":4,\"employeeIds\":[" + emp[0] + "]}");
        json(post("/api/payroll/payslips/generate"), owner, "{\"year\":2025,\"month\":5,\"employeeIds\":[" + emp[0] + "]}");
        String april = payslipId(emp[0], 2025, 4);
        String may = payslipId(emp[0], 2025, 5);
        // two unpaid days in May (26 working days): earnings for 24
        String mayLines = body(mockMvc.perform(get("/api/payroll/payslips/" + may).header("Authorization", auth(owner))));
        json(put("/api/payroll/payslips/" + may), owner, "{\"payDate\":\"2025-05-31\",\"lopDays\":2,\"lines\":[],\"publish\":false}")
                .andExpect(jsonPath("$.paidDays").value(24)).andExpect(jsonPath("$.lopDays").value(2));
        assertThat(mayLines).contains("\"paidDays\":26");

        json(post("/api/payroll/payslips/publish"), owner, "{\"ids\":[" + april + "," + may + "]}")
                .andExpect(jsonPath("$.published").value(2));
        mockMvc.perform(get("/api/my-pay").param("fy", "2025").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.payslips.length()").value(0));
        json(post("/api/payroll/payslips/payments"), owner, "{\"ids\":[" + april + "," + may + "],\"paidOn\":\"2025-06-01\",\"mode\":\"CASH\",\"reference\":\"VCH-0601\",\"remarks\":\"Cash voucher\"}")
                .andExpect(jsonPath("$.credited").value(2));
        mockMvc.perform(get("/api/payroll/payslips/" + april).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.paymentRef").value("VCH-0601")).andExpect(jsonPath("$.paymentRemarks").value("Cash voucher"));
        // May's year to date = April + May
        mockMvc.perform(get("/api/my-pay/payslips/" + may).header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.lines[?(@.code == 'TDS')].ytd").value(hasItem(49914.00)))
                .andExpect(jsonPath("$.lines[?(@.code == 'BASIC')].ytd").value(hasItem(195556.00)));

        mockMvc.perform(get("/api/my-pay").param("fy", "2025").header("Authorization", auth(emp[1])))
                .andExpect(jsonPath("$.fyLabel").value("FY 2025-26"))
                .andExpect(jsonPath("$.salary.set").value(true))
                .andExpect(jsonPath("$.dashboard.expectedGross").value(2863824.00))
                .andExpect(jsonPath("$.dashboard.earnedGross").value(477304.00))
                .andExpect(jsonPath("$.dashboard.taxesPaid").value(52414.00))
                .andExpect(jsonPath("$.dashboard.expectedTax").value(314484.00))
                .andExpect(jsonPath("$.dashboard.balanceGross").value(2386520.00))
                .andExpect(jsonPath("$.dashboard.monthsPaid").value(2))
                .andExpect(jsonPath("$.months.length()").value(12))
                .andExpect(jsonPath("$.months[0].label").value("Apr"))
                .andExpect(jsonPath("$.months[1].net").value(214945.00))
                .andExpect(jsonPath("$.months[2].payslipId").value(nullValue()))
                .andExpect(jsonPath("$.payslips[0].label").value("May 2025"));

        // undo the credit and back to draft: the employee no longer sees it; a draft can be deleted
        mockMvc.perform(delete("/api/payroll/payslips/" + may + "/payment").header("Authorization", auth(owner)));
        mockMvc.perform(post("/api/payroll/payslips/" + may + "/unpublish").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(get("/api/my-pay/payslips/" + may).header("Authorization", auth(emp[1]))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/payroll/payslips/" + may).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/payroll/payslips").param("year", "2025").param("month", "5").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.rows[?(@.employee.id == " + emp[0] + ")].payslipId").value(hasItem(nullValue())));
    }

    @Test
    void anotherPersonsPayslipStaysPrivate() throws Exception {
        String[] a = employee("2025-04-01");
        String[] b = employee("2025-04-01");
        salary(a[0], "2025-04-01");
        json(post("/api/payroll/payslips/generate"), owner, "{\"year\":2025,\"month\":6,\"employeeIds\":[" + a[0] + "]}");
        String id = payslipId(a[0], 2025, 6);
        json(post("/api/payroll/payslips/publish"), owner, "{\"ids\":[" + id + "]}");
        json(put("/api/payroll/payslips/" + id + "/payment"), owner, "{\"paidOn\":\"2025-07-01\",\"mode\":\"UPI\"}");
        mockMvc.perform(get("/api/my-pay/payslips/" + id).header("Authorization", auth(a[1]))).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMode").value("UPI"));
        mockMvc.perform(get("/api/my-pay/payslips/" + id).header("Authorization", auth(b[1]))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/my-pay/payslips/" + id + "/pdf").header("Authorization", auth(b[1]))).andExpect(status().isNotFound());
    }

    @Test
    void theBrandsDetailsAndLogoOnThePayslip() throws Exception {
        // a separate brand, so the other tests keep the plain DW brand
        String brand = String.valueOf((Object) JsonPath.read(body(json(post("/api/brands"), owner,
                "{\"code\":\"HX\",\"name\":\"Amsidel Home\"}").andExpect(status().isCreated())), "$.id"));
        String details = """
                {"code":"HX","name":"Amsidel Home Appliances","address":"Kakkanad, Kochi, Kerala 682030",
                 "launchedDate":"2024-06-01","owners":"Sibin James ,Anu Thomas,, Ravi Kumar ","gstNumber":"32abcde1234f1z5",
                 "category":"Home Appliances","bankAccount":"50200011122233","ifsc":"hdfc0004321","upi":"Amsidel.Home@OKHDFC",
                 "contactNumber":"+91 484 2400 400","email":"Home@Amsidel.com"}""";
        json(put("/api/brands/" + brand), owner, details).andExpect(status().isOk())
                .andExpect(jsonPath("$.owners").value("Sibin James, Anu Thomas, Ravi Kumar"))
                .andExpect(jsonPath("$.gstNumber").value("32ABCDE1234F1Z5"))
                .andExpect(jsonPath("$.category").value("Home Appliances"))
                .andExpect(jsonPath("$.launchedDate").value("2024-06-01"))
                .andExpect(jsonPath("$.bankAccountNumber").value("50200011122233"))
                .andExpect(jsonPath("$.ifscCode").value("HDFC0004321"))
                .andExpect(jsonPath("$.upiId").value("amsidel.home@okhdfc"))
                .andExpect(jsonPath("$.contactNumber").value("+91 48424 00400"))
                .andExpect(jsonPath("$.email").value("home@amsidel.com"));
        json(put("/api/brands/" + brand), owner, details.replace("Home@Amsidel.com", "not an email")).andExpect(status().isBadRequest());
        json(put("/api/brands/" + brand), owner, details.replace("hdfc0004321", "HDFC123")).andExpect(status().isBadRequest());
        json(put("/api/brands/" + brand), owner, details.replace("Amsidel.Home@OKHDFC", "not-a-upi")).andExpect(status().isBadRequest());
        json(put("/api/brands/" + brand), owner, details.replace("32abcde1234f1z5", "12345")).andExpect(status().isBadRequest());

        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        mockMvc.perform(multipart("/api/brands/" + brand + "/logo")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "logo.png", "image/png", png))
                        .header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.logoUpdatedAt").isNotEmpty());
        mockMvc.perform(multipart("/api/brands/" + brand + "/logo")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes()))
                        .header("Authorization", auth(owner)))
                .andExpect(status().isBadRequest());
        // the logo is public
        mockMvc.perform(get("/api/brands/" + brand + "/logo")).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));

        // a person of that brand: the payslip shows the brand
        String userId = "ps.home" + (++seq);
        String id = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Home Person","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%s,
                         "phone":"+91 98470 00000","joinedDate":"2025-04-01"}""".formatted(userId, brand)))
                .andExpect(status().isCreated())), "$.id"));
        salary(id, "2025-04-01");
        json(post("/api/payroll/payslips/generate"), owner, "{\"year\":2025,\"month\":7,\"employeeIds\":[" + id + "]}");
        String slip = payslipId(id, 2025, 7);
        mockMvc.perform(get("/api/payroll/payslips/" + slip).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.company.name").value("Amsidel Home Appliances"))
                .andExpect(jsonPath("$.company.address").value("Kakkanad, Kochi, Kerala 682030"))
                .andExpect(jsonPath("$.company.gstNumber").value("32ABCDE1234F1Z5"))
                .andExpect(jsonPath("$.company.logoUpdatedAt").isNotEmpty())
                .andExpect(jsonPath("$.company.phone").value("+91 48424 00400"))
                .andExpect(jsonPath("$.company.email").value("home@amsidel.com"));
        byte[] pdf = mockMvc.perform(get("/api/payroll/payslips/" + slip + "/pdf").header("Authorization", auth(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        // only owners change brands
        String[] emp = employee("2025-04-01");
        json(put("/api/brands/" + brand), emp[1], details).andExpect(status().isForbidden());
    }

    @Test
    void bankDetailsFromTheEmployeeScreen() throws Exception {
        String[] a = employee("2025-04-01");
        String[] b = employee("2025-04-01");
        json(put("/api/employees/" + a[0] + "/bank-details"), owner,
                "{\"pan\":\"pqrst6789k\",\"bankAccount\":\"1234567890\",\"ifsc\":\"sbin0004567\",\"upi\":\"Slip@OKSBI\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pan").value("PQRST6789K"))
                .andExpect(jsonPath("$.ifsc").value("SBIN0004567"))
                .andExpect(jsonPath("$.upi").value("slip@oksbi"))
                .andExpect(jsonPath("$.canEdit").value(true));
        json(put("/api/employees/" + a[0] + "/bank-details"), owner, "{\"ifsc\":\"SBI123\"}").andExpect(status().isBadRequest());
        // the person sees their own, read-only; not someone else's; and cannot change them
        mockMvc.perform(get("/api/auth/me/bank-details").header("Authorization", auth(a[1])))
                .andExpect(jsonPath("$.bankAccount").value("1234567890")).andExpect(jsonPath("$.canEdit").value(false));
        mockMvc.perform(get("/api/employees/" + a[0] + "/bank-details").header("Authorization", auth(b[1])))
                .andExpect(status().isForbidden());
        json(put("/api/employees/" + a[0] + "/bank-details"), a[1], "{\"upi\":\"x@ok\"}").andExpect(status().isForbidden());
        // the payroll salary page shows the same details
        mockMvc.perform(get("/api/payroll/salaries/" + a[0]).param("fy", "2025").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.employee.upi").value("slip@oksbi"));
        // the general employee record does not carry them
        mockMvc.perform(get("/api/employees/" + a[0]).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.pan").doesNotExist()).andExpect(jsonPath("$.bankAccount").doesNotExist());
    }

    @Test
    void workingDaysOfTheMonthCanBeChanged() throws Exception {
        String[] emp = employee("2025-04-01");
        salary(emp[0], "2025-04-01");
        mockMvc.perform(get("/api/payroll/salaries/" + emp[0]).param("fy", "2025").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.workingDays").value(26));
        // June made with 25 working days: a full month is 25 paid days, the full amounts
        json(post("/api/payroll/payslips/generate"), owner, "{\"year\":2025,\"month\":6,\"workingDays\":25,\"employeeIds\":[" + emp[0] + "]}")
                .andExpect(jsonPath("$.created").value(1));
        String id = payslipId(emp[0], 2025, 6);
        mockMvc.perform(get("/api/payroll/payslips/" + id).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.workingDays").value(25)).andExpect(jsonPath("$.paidDays").value(25))
                .andExpect(jsonPath("$.lines[?(@.code == 'BASIC')].amount").value(hasItem(97778.00)));
        // then 22 working days and one LOP day: 21 paid; LOP cannot pass the working days
        json(put("/api/payroll/payslips/" + id), owner, "{\"payDate\":\"2025-06-30\",\"workingDays\":22,\"lopDays\":1,\"lines\":[],\"publish\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDays").value(22)).andExpect(jsonPath("$.paidDays").value(21));
        json(put("/api/payroll/payslips/" + id), owner, "{\"payDate\":\"2025-06-30\",\"workingDays\":22,\"lopDays\":23,\"lines\":[],\"publish\":false}")
                .andExpect(status().isBadRequest());
        // recalculating from the salary keeps the month's working days and LOP: BASIC = 97778 / 22 x 21
        mockMvc.perform(post("/api/payroll/payslips/" + id + "/recalculate").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.workingDays").value(22)).andExpect(jsonPath("$.paidDays").value(21))
                .andExpect(jsonPath("$.lines[?(@.code == 'BASIC')].amount").value(hasItem(93334.00)));
        // the salary's own working days apply to new payslips
        json(post("/api/payroll/payslips/generate"), owner, "{\"year\":2025,\"month\":7,\"employeeIds\":[" + emp[0] + "]}");
        mockMvc.perform(get("/api/payroll/payslips/" + payslipId(emp[0], 2025, 7)).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.workingDays").value(26));
    }

    @Test
    void indianFormatting() {
        assertThat(com.amsidel.backend.service.RupeesInWords.of(new java.math.BigDecimal("12500050.50")))
                .isEqualTo("Indian Rupee One Crore Twenty-Five Lakh Fifty and Fifty Paise Only");
    }

    // ---- helpers ----

    /** The September 2025 payslip as the salary. */
    private void salary(String employeeId, String from) throws Exception {
        String lines = "[" + String.join(",",
                line("BASIC", "97778"), line("DA", "24444"), line("HRA", "39111"), line("LTA", "3667"), line("SPECIAL", "73652"),
                line("EPF", "1800"), line("LWF", "100"), line("TDS", "24957"), line("PT", "1250"),
                line("FUEL", "2933"), line("PHONE", "1467")) + "]";
        json(put("/api/payroll/salaries/" + employeeId + "?fy=2025"), owner,
                "{\"effectiveFrom\":\"%s\",\"notes\":\"\",\"lines\":%s,\"pan\":\"abcde1234f\",\"bankAccount\":\"50100012345662\",\"ifsc\":\"hdfc0001234\",\"upi\":\"Anu.T@OKHDFC\"}"
                        .formatted(from, lines))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.pan").value("ABCDE1234F"))
                .andExpect(jsonPath("$.employee.ifsc").value("HDFC0001234"))
                .andExpect(jsonPath("$.employee.upi").value("anu.t@okhdfc"));
    }

    /** Every line of the payslip at its full monthly amount. */
    private String fullMonthLines(String payslipId) throws Exception {
        String view = body(mockMvc.perform(get("/api/payroll/payslips/" + payslipId).header("Authorization", auth(owner))));
        List<Map<String, Object>> lines = JsonPath.read(view, "$.lines");
        return lines.stream().map(l -> "{\"id\":%s,\"amount\":%s}".formatted(l.get("id"), l.get("fullAmount")))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String payslipId(String employeeId, int year, int month) throws Exception {
        String list = body(mockMvc.perform(get("/api/payroll/payslips").param("year", String.valueOf(year))
                .param("month", String.valueOf(month)).header("Authorization", auth(owner))));
        List<Integer> ids = JsonPath.read(list, "$.rows[?(@.employee.id == " + employeeId + ")].payslipId");
        return String.valueOf(ids.get(0));
    }

    private String line(String code, String monthly) {
        return "{\"componentId\":%d,\"monthly\":%s}".formatted(components.get(code), monthly);
    }

    /** {id, token} of a new employee of DW. */
    private String[] employee(String joined) throws Exception {
        String userId = "ps.emp" + (++seq);
        String json = """
                {"name":"Slip %d","userId":"%s","password":"ab@123","role":"EMPLOYEE","brandId":%d,
                 "phone":"+91 98470 00000","joinedDate":"%s"}
                """.formatted(seq, userId, dw, joined);
        String id = String.valueOf((Object) JsonPath.read(body(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated())), "$.id"));
        return new String[]{id, token(changePin(token(login(userId, "ab@123")), "ab@123", "cd@456"))};
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token,
                               String body) throws Exception {
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
