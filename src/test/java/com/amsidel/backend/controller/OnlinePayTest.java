package com.amsidel.backend.controller;

import com.amsidel.backend.service.RazorpayGateway;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Pay bill: a bill paid online by card and by UPI through the brand's Razorpay account (a fake Razorpay here), with
 * the processing fee on top; verified, recorded on the invoice, the card or UPI ID kept for refunds; refunds; who may.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=onlinepay", "app.razorpay.key-id=", "app.razorpay.key-secret=", "app.razorpay.methods-cache=PT0S"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnlinePayTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final String KEY = "rzp_test_Abc123Xyz";
    private static final String SECRET = "s3cretKeyForTests42";

    /** Razorpay as the portal sees it: orders, payments made on them, captures and refunds. */
    static class FakeRazorpay implements RazorpayGateway {
        final Map<String, Order> orders = new LinkedHashMap<>();
        final Map<String, Payment> payments = new LinkedHashMap<>();
        final List<Refund> refunds = new ArrayList<>();
        final List<String> captured = new ArrayList<>();
        Methods methods = new Methods(true, true, true);
        int seq;

        @Override
        public Methods methods(String keyId) {
            return methods;
        }

        @Override
        public Order createOrder(Keys keys, long amount, String receipt, Map<String, String> notes) {
            assertThat(keys.keyId()).isEqualTo(KEY);
            Order o = new Order("order_T" + (++seq), amount, "INR", "created");
            orders.put(o.id(), o);
            return o;
        }

        Payment paid(String orderId, String status, boolean card) {
            Order o = orders.get(orderId);
            Payment p = card
                    ? new Payment("pay_T" + (++seq), orderId, status, o.amount(), "INR", "card", "Visa", "1111", "HDFC", "credit", false,
                    null, null, null, "kumar@example.com", "+919847022222", null)
                    : new Payment("pay_T" + (++seq), orderId, status, o.amount(), "INR", "upi", null, null, null, null, null,
                    "kumar@okhdfcbank", null, null, "kumar@example.com", "+919847022222", null);
            payments.put(p.id(), p);
            return p;
        }

        @Override
        public Payment payment(Keys keys, String paymentId) {
            return payments.get(paymentId);
        }

        @Override
        public List<Payment> orderPayments(Keys keys, String orderId) {
            return payments.values().stream().filter(p -> p.orderId().equals(orderId)).toList();
        }

        @Override
        public Payment capture(Keys keys, String paymentId, long amount, String currency) {
            Payment p = payments.get(paymentId);
            Payment c = new Payment(p.id(), p.orderId(), "captured", p.amount(), p.currency(), p.method(), p.cardNetwork(), p.cardLast4(),
                    p.cardIssuer(), p.cardType(), p.cardInternational(), p.vpa(), p.bank(), p.wallet(), p.email(), p.contact(), null);
            payments.put(p.id(), c);
            captured.add(p.id());
            return c;
        }

        @Override
        public Refund refund(Keys keys, String paymentId, long amount, Map<String, String> notes) {
            Refund r = new Refund("rfnd_T" + (++seq), amount, "processed");
            refunds.add(r);
            return r;
        }
    }

    @TestConfiguration
    static class FakeGateway {
        @Bean
        @Primary
        FakeRazorpay fakeRazorpay() {
            return new FakeRazorpay();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeRazorpay razorpay;

    private String owner;
    private long dw;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.getFirst();
    }

    @Test
    void billPaidByCardAndUpiWithTheFeeOnTopThenRefunded() throws Exception {
        String customer = idOf(json(post("/api/trade/customers"), owner, """
                {"brandId":%d,"name":"Kumar Residence","phone":"9847022222","email":"kumar@example.com"}
                """.formatted(dw)).andExpect(status().isCreated()));
        String invoice = idOf(json(post("/api/trade/sales"), owner, """
                {"kind":"INVOICE","brandId":%d,"customerId":%s,"docDate":"%s","gstRate":0,
                 "lines":[{"name":"Windows fixing","quantity":1,"rate":10000}]}
                """.formatted(dw, customer, TODAY)).andExpect(status().isCreated()));
        String number = JsonPath.read(body(mockMvc.perform(post("/api/trade/sales/" + invoice + "/issue").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("ISSUED"))), "$.number");

        // the fees start at 3%; the brand has no Razorpay keys yet
        mockMvc.perform(get("/api/trade/pay/setup").header("Authorization", auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fees[?(@.method == 'CARD')].percent").value(hasItem(3.0)))
                .andExpect(jsonPath("$.fees[?(@.method == 'UPI')].percent").value(hasItem(3.0)))
                .andExpect(jsonPath("$.brands[?(@.code == 'DW')].ready").value(hasItem(false)))
                .andExpect(jsonPath("$.canRefund").value(true));
        start(invoice, "CARD", "4000").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("Razorpay keys")));

        // the owner adds the brand's keys; the secret is never sent back
        json(put("/api/brands/" + dw + "/razorpay"), owner, "{\"keyId\":\"rzp_test_Other999\",\"keySecret\":\"\"}")
                .andExpect(status().isBadRequest());
        json(put("/api/brands/" + dw + "/razorpay"), owner, "{\"keyId\":\"%s\",\"keySecret\":\"%s\"}".formatted(KEY, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razorpayKeyId").value(KEY))
                .andExpect(jsonPath("$.razorpayReady").value(true))
                .andExpect(jsonPath("$.razorpayKeySecret").doesNotExist());
        String brands = body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner))));
        assertThat(brands).doesNotContain(SECRET);

        // brand > customer > bill: what is still due
        mockMvc.perform(get("/api/trade/pay/customers").param("brandId", String.valueOf(dw)).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.name == 'Kumar Residence')].due").value(hasItem(10000.0)));
        mockMvc.perform(get("/api/trade/pay/bills").param("customerId", customer).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[0].number").value(number))
                .andExpect(jsonPath("$[0].due").value(10000.0));

        // card: 4,000 off the bill + 3% = 4,120 charged
        start(invoice, "CARD", "10001").andExpect(status().isBadRequest());
        String checkout = body(start(invoice, "CARD", "4000").andExpect(status().isOk())
                .andExpect(jsonPath("$.keyId").value(KEY))
                .andExpect(jsonPath("$.amount").value(412000))
                .andExpect(jsonPath("$.fee").value(120.0))
                .andExpect(jsonPath("$.total").value(4120.0))
                .andExpect(jsonPath("$.customerEmail").value("kumar@example.com")));
        String cardId = String.valueOf((Object) JsonPath.read(checkout, "$.id"));
        String cardOrder = JsonPath.read(checkout, "$.orderId");
        var card = razorpay.paid(cardOrder, "captured", true);

        // a made-up signature is refused; Razorpay's is accepted
        json(post("/api/trade/pay/orders/" + cardId + "/verify"), owner,
                "{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"signature\":\"%s\"}".formatted(card.id(), cardOrder, "ab12"))
                .andExpect(status().isBadRequest());
        verify(cardId, card.id(), cardOrder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentId").value(card.id()))
                .andExpect(jsonPath("$.billNumber").value(number))
                .andExpect(jsonPath("$.source.cardNetwork").value("Visa"))
                .andExpect(jsonPath("$.source.cardLast4").value("1111"))
                .andExpect(jsonPath("$.source.label").value("Visa credit card •••• 1111 · HDFC"));
        // asked again (e.g. a double click): recorded once
        verify(cardId, card.id(), cardOrder).andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(get("/api/trade/sales/" + invoice).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.totals.paid").value(4000.0))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].mode").value("CARD"))
                .andExpect(jsonPath("$.payments[0].transactionId").value(card.id()))
                .andExpect(jsonPath("$.payments[0].bankDetails").value("Visa credit card •••• 1111 · HDFC"))
                .andExpect(jsonPath("$.payments[0].remarks", containsString("processing fee (3%)")));

        // UPI with the fee set to 0%: the window is closed first, then paid; the browser lost it, Razorpay is asked
        json(put("/api/trade/pay/fees"), owner, "{\"method\":\"UPI\",\"percent\":0}").andExpect(jsonPath("$.percent").value(0.0));
        String upiCheckout = body(start(invoice, "UPI", "6000").andExpect(jsonPath("$.total").value(6000.0)).andExpect(jsonPath("$.amount").value(600000)));
        String upiId = String.valueOf((Object) JsonPath.read(upiCheckout, "$.id"));
        String upiOrder = JsonPath.read(upiCheckout, "$.orderId");
        json(post("/api/trade/pay/orders/" + upiId + "/fail"), owner, "{\"cancelled\":true}").andExpect(jsonPath("$.status").value("CANCELLED"));
        var upi = razorpay.paid(upiOrder, "authorized", false);
        mockMvc.perform(post("/api/trade/pay/orders/" + upiId + "/refresh").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.source.vpa").value("kumar@okhdfcbank"));
        assertThat(razorpay.captured).containsExactly(upi.id());
        mockMvc.perform(get("/api/trade/sales/" + invoice).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.payStatus").value("PAID"))
                .andExpect(jsonPath("$.payments[1].mode").value("UPI"))
                .andExpect(jsonPath("$.payments[1].upiId").value("kumar@okhdfcbank"));
        start(invoice, "CARD", "1").andExpect(status().isBadRequest());

        // the history and where the money came from
        mockMvc.perform(get("/api/trade/pay/history").param("from", TODAY.toString()).param("to", TODAY.toString()).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].method").value("UPI"))
                .andExpect(jsonPath("$[1].fee").value(120.0));
        mockMvc.perform(get("/api/trade/pay/sources").param("brandId", String.valueOf(dw)).param("from", TODAY.toString()).param("to", TODAY.toString())
                        .header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.transactionId == '%s')].cardLast4".formatted(card.id())).value(hasItem("1111")))
                .andExpect(jsonPath("$[?(@.transactionId == '%s')].vpa".formatted(upi.id())).value(hasItem("kumar@okhdfcbank")))
                .andExpect(jsonPath("$[?(@.transactionId == '%s')].billNumber".formatted(upi.id())).value(hasItem(number)))
                .andExpect(jsonPath("$[0].customerName").value("Kumar Residence"));

        // a refund goes back through Razorpay and onto the bill; not more than was paid (the fee stays)
        refund(cardId, "1000").andExpect(status().isOk()).andExpect(jsonPath("$.refunded").value(1000.0));
        assertThat(razorpay.refunds).extracting(RazorpayGateway.Refund::amount).containsExactly(100000L);
        refund(cardId, "3500").andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/trade/sales/" + invoice).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.totals.paid").value(9000.0))
                .andExpect(jsonPath("$.payments[2].kind").value("REFUND"))
                .andExpect(jsonPath("$.payments[2].transactionId").value(razorpay.refunds.getFirst().id()));

        // a refused card
        String again = body(start(invoice, "CARD", "1000"));
        String againId = String.valueOf((Object) JsonPath.read(again, "$.id"));
        json(post("/api/trade/pay/orders/" + againId + "/fail"), owner, "{\"cancelled\":false,\"reason\":\"Card declined by the bank\"}")
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.failure").value("Card declined by the bank"));

        // without the menu: no; staff given the menu take payments, but only owners and admins change the fee or refund
        String[] staff = employee("EMPLOYEE");
        String employee = staff[1];
        mockMvc.perform(get("/api/trade/pay/setup").header("Authorization", auth(employee))).andExpect(status().isForbidden());
        json(put("/api/brands/" + dw + "/razorpay"), employee, "{\"keyId\":\"\",\"keySecret\":\"\"}").andExpect(status().isForbidden());
        json(put("/api/menu-access/EMPLOYEE/" + staff[0]), owner, "{\"checkpoints\":[\"11800\"]}").andExpect(status().isOk());
        mockMvc.perform(get("/api/trade/pay/setup").header("Authorization", auth(employee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canSetFees").value(false)).andExpect(jsonPath("$.canRefund").value(false));
        // the brand picker every product-portal page loads
        mockMvc.perform(get("/api/trade/brands").header("Authorization", auth(employee))).andExpect(status().isOk());
        json(put("/api/trade/pay/fees"), employee, "{\"method\":\"CARD\",\"percent\":1}").andExpect(status().isForbidden());
        json(post("/api/trade/pay/orders/" + cardId + "/refund"), employee, "{\"amount\":1,\"reason\":\"x\"}").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/trade/pay/setup").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.fees[?(@.method == 'CARD')].percent").value(hasItem(3.0)));

        // an account without UPI switched on: shown as such, and a UPI payment is refused before any order is made
        razorpay.methods = new RazorpayGateway.Methods(true, false, true);
        int orders = razorpay.orders.size();
        mockMvc.perform(get("/api/trade/pay/setup").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.brands[?(@.code == 'DW')].upi").value(hasItem(false)))
                .andExpect(jsonPath("$.brands[?(@.code == 'DW')].card").value(hasItem(true)));
        start(invoice, "UPI", "500").andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("UPI is not switched on")));
        assertThat(razorpay.orders).hasSize(orders);

        // manual UPI at the office: given on top of Pay bill by its own checkpoint (11801)
        mockMvc.perform(get("/api/trade/pay/setup").header("Authorization", auth(employee))).andExpect(jsonPath("$.canManual").value(false));
        json(post("/api/trade/pay/orders"), employee, "{\"invoiceId\":%s,\"method\":\"MANUAL_UPI\",\"amount\":500}".formatted(invoice))
                .andExpect(status().isForbidden());
        json(put("/api/menu-access/EMPLOYEE/" + staff[0]), owner, "{\"checkpoints\":[\"11800\",\"11801\"]}").andExpect(status().isOk());
        mockMvc.perform(get("/api/trade/pay/setup").header("Authorization", auth(employee)))
                .andExpect(jsonPath("$.canManual").value(true))
                .andExpect(jsonPath("$.brands[?(@.code == 'DW')].manualReady").value(hasItem(true)))
                .andExpect(jsonPath("$.fees[?(@.method == 'MANUAL_UPI')].percent").value(hasItem(0.0)));
        String manual = body(json(post("/api/trade/pay/orders"), employee, "{\"invoiceId\":%s,\"method\":\"MANUAL_UPI\",\"amount\":1000}".formatted(invoice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").doesNotExist())
                .andExpect(jsonPath("$.fee").value(0.0))
                .andExpect(jsonPath("$.total").value(1000.0))
                .andExpect(jsonPath("$.payeeUpiId").isNotEmpty()));
        String manualId = String.valueOf((Object) JsonPath.read(manual, "$.id"));
        assertThat(razorpay.orders).hasSize(orders);
        byte[] qr = mockMvc.perform(get("/api/trade/pay/orders/" + manualId + "/qr").header("Authorization", auth(employee)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String read = com.amsidel.backend.service.UpiQr.read(qr);
        assertThat(read).startsWith("upi://pay?pa=").contains("am=1000.00").contains("tn=" + number);
        json(post("/api/trade/pay/orders/" + manualId + "/manual"), employee, "{\"reference\":\"12-34\",\"paidOn\":\"%s\"}".formatted(TODAY))
                .andExpect(status().isBadRequest());
        json(post("/api/trade/pay/orders/" + manualId + "/manual"), employee,
                "{\"reference\":\"412345678901\",\"payerUpiId\":\"Kumar@OKAXIS\",\"paidOn\":\"%s\",\"remarks\":\"Paid at the Kochi showroom\"}".formatted(TODAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.method").value("MANUAL_UPI"))
                .andExpect(jsonPath("$.paymentId").value("412345678901"))
                .andExpect(jsonPath("$.source.label").value("UPI at the office from kumar@okaxis"));
        mockMvc.perform(get("/api/trade/sales/" + invoice).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.payStatus").value("PAID"))
                .andExpect(jsonPath("$.payments[?(@.transactionId == '412345678901')].mode").value(hasItem("UPI")))
                .andExpect(jsonPath("$.payments[?(@.transactionId == '412345678901')].remarks").value(hasItem(containsString("recorded by Pay Person"))));
        // the same UPI reference cannot be recorded twice; no Razorpay refund for money paid at the office
        String other = idOf(json(post("/api/trade/sales"), owner, """
                {"kind":"INVOICE","brandId":%d,"customerId":%s,"docDate":"%s","gstRate":0,"lines":[{"name":"Mesh","quantity":1,"rate":800}]}
                """.formatted(dw, customer, TODAY)));
        mockMvc.perform(post("/api/trade/sales/" + other + "/issue").header("Authorization", auth(owner)));
        String again2 = String.valueOf((Object) JsonPath.read(body(json(post("/api/trade/pay/orders"), employee,
                "{\"invoiceId\":%s,\"method\":\"MANUAL_UPI\",\"amount\":800}".formatted(other))), "$.id"));
        json(post("/api/trade/pay/orders/" + again2 + "/manual"), employee, "{\"reference\":\"412345678901\",\"paidOn\":\"%s\"}".formatted(TODAY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("already recorded")));
        refund(manualId, "100").andExpect(status().isConflict());
        mockMvc.perform(get("/api/trade/pay/history").param("from", TODAY.toString()).param("to", TODAY.toString()).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$[?(@.method == 'MANUAL_UPI' && @.status == 'PAID')].payeeUpiId").isNotEmpty());
    }

    // ---- helpers ----

    private ResultActions start(String invoice, String method, String amount) throws Exception {
        return json(post("/api/trade/pay/orders"), owner, "{\"invoiceId\":%s,\"method\":\"%s\",\"amount\":%s}".formatted(invoice, method, amount));
    }

    private ResultActions verify(String id, String paymentId, String orderId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
        return json(post("/api/trade/pay/orders/" + id + "/verify"), owner,
                "{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"signature\":\"%s\"}".formatted(paymentId, orderId, signature));
    }

    private ResultActions refund(String id, String amount) throws Exception {
        return json(post("/api/trade/pay/orders/" + id + "/refund"), owner, "{\"amount\":%s,\"reason\":\"One window returned\"}".formatted(amount));
    }

    /** A new person of DW: {employee id, token}. */
    private String[] employee(String role) throws Exception {
        String id = idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Pay Person\",\"userId\":\"op.p1\",\"password\":\"ab@123\",\"role\":\"%s\",\"brandId\":%d,\"phone\":\"+91 98470 00000\",\"joinedDate\":\"2026-01-05\"}"
                        .formatted(role, dw))).andExpect(status().isCreated()));
        return new String[] {id, token(changePin(token(login("op.p1", "ab@123")), "ab@123", "cd@456"))};
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
