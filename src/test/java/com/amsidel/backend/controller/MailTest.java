/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff mail: one personal mail per employee, to their office address; failures come back per person. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=mail", "spring.mail.host=localhost", "spring.mail.username=", "app.mail.from=staff@amsidel.com"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailTest {

    /** Keeps the mails instead of sending them; an address containing "bounce" fails like a refused recipient. */
    static class FakeMailSender extends JavaMailSenderImpl {
        final List<MimeMessage> sent = new ArrayList<>();

        @Override
        protected void doSend(MimeMessage[] messages, Object[] originals) {
            for (MimeMessage m : messages) {
                try {
                    if (Arrays.stream(m.getAllRecipients()).map(Address::toString).anyMatch(a -> a.contains("bounce"))) {
                        // how a refused recipient really arrives: wrapped twice
                        throw new MailSendException(java.util.Map.of(m, new jakarta.mail.SendFailedException("Invalid Addresses",
                                new jakarta.mail.MessagingException("550 5.1.1 Mailbox unavailable\n"))));
                    }
                } catch (jakarta.mail.MessagingException e) {
                    throw new MailSendException("bad message", e);
                }
                sent.add(m);
            }
        }
    }

    /** A brand's Brevo account that keeps its mails too; the key "wrong" is refused. */
    static class FakeBrandSenders extends com.amsidel.backend.service.BrandMailSenders {
        final Map<String, FakeMailSender> byLogin = new java.util.concurrent.ConcurrentHashMap<>();

        FakeBrandSenders(com.amsidel.backend.repository.MailBrandAccountRepository accounts) {
            super(accounts, "smtp-relay.brevo.com", 2525);
        }

        @Override
        protected JavaMailSenderImpl build(String login, String key) {
            FakeMailSender s = new FakeMailSender() {
                @Override
                public void testConnection() throws jakarta.mail.MessagingException {
                    if ("wrong".equals(key)) {
                        throw new jakarta.mail.AuthenticationFailedException("535 Authentication failed");
                    }
                }
            };
            byLogin.put(login, s);
            return s;
        }
    }

    @TestConfiguration
    static class FakeMail {
        @Bean
        FakeMailSender javaMailSender() {
            return new FakeMailSender();
        }

        @Bean
        @org.springframework.context.annotation.Primary
        FakeBrandSenders fakeBrandSenders(com.amsidel.backend.repository.MailBrandAccountRepository accounts) {
            return new FakeBrandSenders(accounts);
        }
    }

    @Autowired
    private FakeBrandSenders brandSenders;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeMailSender mail;

    private String owner;
    private String employee;
    private String anu;
    private String ravi;
    private String noEmail;
    private long dw;
    /** A brand admin. */
    private String admin;
    private String bounce;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        dw = brandIds.get(0);
        anu = create("Anu Thomas", "ml.anu", "anu@amsidel.com", dw);
        ravi = create("Ravi Kumar", "ml.ravi", "ravi@amsidel.com", dw);
        noEmail = create("No Mail", "ml.none", null, dw);
        bounce = create("Bounce Back", "ml.bounce", "bounce@amsidel.com", dw);
        create("Mail Admin", "ml.admin", null, dw, "ADMIN");
        employee = token(changePin(token(login("ml.anu", "ab@123")), "ab@123", "cd@456"));
        admin = token(changePin(token(login("ml.admin", "ab@123")), "ab@123", "cd@456"));
    }

    @BeforeEach
    void clear() {
        mail.sent.clear();
    }

    @Test
    void eachPersonGetsTheirOwnMailWithTheirName() throws Exception {
        String subject = "New client: Kumar residence ({name})";
        send(owner, anu, subject, "", "Please note the site visit on Monday.\nThanks, {name}.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.email").value("anu@amsidel.com"));
        send(owner, ravi, subject, "All", "Please note the site visit on Monday.")
                .andExpect(jsonPath("$.sent").value(true));

        assertThat(mail.sent).hasSize(2);
        MimeMessage first = mail.sent.get(0);
        // the office is copied on every mail
        assertThat(first.getRecipients(Message.RecipientType.TO)).extracting(Address::toString).containsExactly("anu@amsidel.com");
        assertThat(first.getRecipients(Message.RecipientType.CC)).extracting(Address::toString).containsExactly("info.amsidel@gmail.com");
        assertThat(first.getSubject()).isEqualTo("New client: Kumar residence (Anu Thomas)");
        assertThat(first.getFrom()[0].toString()).contains("staff@amsidel.com");
        assertThat(text(first)).contains("Dear Anu Thomas,").contains("Thanks, Anu Thomas.");
        assertThat(text(mail.sent.get(1))).contains("Dear All,");
    }

    @Test
    void customerAndSupplierMailLikeStaffMailWithTheOfficeCopy() throws Exception {
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        long dw = brandIds.getFirst();
        String customer = idOf(mockMvc.perform(post("/api/trade/customers").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"Varghese Villa\",\"phone\":\"9847033333\",\"email\":\"varghese@example.com\"}".formatted(dw))));
        String noMail = idOf(mockMvc.perform(post("/api/trade/customers").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"No Mail Home\",\"phone\":\"9847044444\"}".formatted(dw))));
        String supplier = idOf(mockMvc.perform(post("/api/trade/suppliers").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"Silver Frames\",\"phone\":\"9847011111\",\"email\":\"orders@silverframes.in\"}".formatted(dw))));

        // who can be mailed
        mockMvc.perform(get("/api/trade/mail/recipients").param("brandId", String.valueOf(dw)).header("Authorization", auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers[?(@.name == 'Varghese Villa')].email").value(org.hamcrest.Matchers.hasItem("varghese@example.com")))
                .andExpect(jsonPath("$.suppliers[?(@.name == 'Silver Frames')].party").value(org.hamcrest.Matchers.hasItem("SUPPLIER")));
        mockMvc.perform(get("/api/trade/mail/setup").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.alwaysCc").value("info.amsidel@gmail.com"));

        // the same mail as staff mail: their name, the signature, the office copy; signed by the brand
        String json = """
                {"party":"CUSTOMER","partyId":%s,"subject":"Your quotation, {name}","greeting":"","body":"Please find the **revised** quotation.",
                 "cc":["INFO.amsidel@gmail.com"],"signature":"Regards,\\n**Sales team**"}
                """.formatted(customer);
        mockMvc.perform(post("/api/trade/mail/send").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(true)).andExpect(jsonPath("$.email").value("varghese@example.com"));
        MimeMessage m = mail.sent.getFirst();
        assertThat(m.getRecipients(Message.RecipientType.TO)).extracting(Address::toString).containsExactly("varghese@example.com");
        // the office address is not copied twice when it is typed in as well
        assertThat(m.getRecipients(Message.RecipientType.CC)).extracting(Address::toString).hasSize(1)
                .allMatch(a -> a.equalsIgnoreCase("info.amsidel@gmail.com"));
        assertThat(m.getSubject()).isEqualTo("Your quotation, Varghese Villa");
        assertThat(text(m)).contains("Dear Varghese Villa,").contains("<b>revised</b>").contains("Sent by Amsidel Doors &amp; Windows");

        mockMvc.perform(post("/api/trade/mail/send").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"party\":\"SUPPLIER\",\"partyId\":%s,\"subject\":\"Order\",\"body\":\"Please send 20 frames.\"}".formatted(supplier)))
                .andExpect(jsonPath("$.sent").value(true));
        assertThat(mail.sent.get(1).getRecipients(Message.RecipientType.TO)).extracting(Address::toString).containsExactly("orders@silverframes.in");
        assertThat(mail.sent.get(1).getRecipients(Message.RecipientType.CC)).extracting(Address::toString).containsExactly("info.amsidel@gmail.com");

        // no email: not sent, said so
        mockMvc.perform(post("/api/trade/mail/send").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"party\":\"CUSTOMER\",\"partyId\":%s,\"subject\":\"Hi\",\"body\":\"Hello\"}".formatted(noMail)))
                .andExpect(jsonPath("$.sent").value(false)).andExpect(jsonPath("$.error").value("No email on record"));
        // an employee without the menu
        mockMvc.perform(post("/api/trade/mail/send").header("Authorization", auth(employee)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"party\":\"CUSTOMER\",\"partyId\":%s,\"subject\":\"Hi\",\"body\":\"Hello\"}".formatted(customer)))
                .andExpect(status().isForbidden());
        assertThat(mail.sent).hasSize(2);
    }

    @Test
    void ccBccAttachmentsAndPicturesInTheMessage() throws Exception {
        String pdf = fileId(upload(owner, "Leave policy.pdf", "%PDF-1.7 test".getBytes()).andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.image").value(false)));
        String png = fileId(upload(owner, "office.png", PNG).andExpect(status().isOk())
                .andExpect(jsonPath("$.inline").value(true)));
        String json = """
                {"employeeId":%s,"subject":"Office move","greeting":"","body":"We are moving.\\n[[image 1]]\\nSee the policy.",
                 "cc":["hr@amsidel.com","HR@amsidel.com"],"bcc":["boss@amsidel.com"],
                 "attachments":["%s"],"images":[{"fileId":"%s","caption":"The new office <Kochi>"}]}
                """.formatted(anu, pdf, png);
        mockMvc.perform(post("/api/mail/send").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(true));

        MimeMessage m = mail.sent.getFirst();
        assertThat(m.getRecipients(Message.RecipientType.TO)).extracting(Address::toString).containsExactly("anu@amsidel.com");
        assertThat(m.getRecipients(Message.RecipientType.CC)).extracting(Address::toString)
                .containsExactly("hr@amsidel.com", "info.amsidel@gmail.com");
        assertThat(m.getRecipients(Message.RecipientType.BCC)).extracting(Address::toString).containsExactly("boss@amsidel.com");
        String all = text(m);
        assertThat(all).contains("src=\"cid:picture1\"").contains("The new office &lt;Kochi&gt;")
                .contains("[Picture: The new office <Kochi>]").doesNotContain("[[image 1]]");
        List<String> names = new ArrayList<>();
        parts(m.getContent(), names);
        assertThat(names).contains("Leave policy.pdf");
    }

    @Test
    void boldHeadingsPicturePlacesAndMySignature() throws Exception {
        // the standard signature until one is saved
        mockMvc.perform(get("/api/mail/signature").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.signature").value(org.hamcrest.Matchers.startsWith("Regards,\n**")));
        mockMvc.perform(put("/api/mail/signature").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signature\":\"Warm regards,\\n**The Owner**\\nAmsidel Group\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));

        String png = fileId(upload(owner, "office.png", PNG).andExpect(status().isOk()));
        String json = """
                {"employeeId":%s,"subject":"Timings","greeting":"","body":"# New **office** timings\\nWe open at **9 am** sharp.\\n[[image 1]]",
                 "images":[{"fileId":"%s","caption":"Front desk","align":"RIGHT","size":"SMALL"}]}
                """.formatted(anu, png);
        mockMvc.perform(post("/api/mail/send").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(true));
        String all = text(mail.sent.getFirst());
        assertThat(all).contains("font-weight:700;color:#1d5fa3\">New office timings</div>")
                .contains("We open at <b>9 am</b> sharp.")
                .contains("align=\"right\" style=\"margin:16px 0;text-align:right\"")
                .contains("<b>The Owner</b>").contains("Warm regards,")
                .contains("background:#eef4fb")
                // the plain text part has no marks
                .contains("NEW OFFICE TIMINGS").contains("We open at 9 am sharp.").doesNotContain("**");

        // a bad position is refused; an empty signature goes back to the standard one
        mockMvc.perform(post("/api/mail/send").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.replace("RIGHT", "MIDDLE"))).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/mail/signature").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signature\":\"  \"}"))
                .andExpect(jsonPath("$.saved").value(false));
    }

    @Test
    void onlyPicturesPdfAndWordAndOnlyYourOwnFiles() throws Exception {
        upload(owner, "virus.exe", "MZ...".getBytes()).andExpect(status().isBadRequest());
        upload(owner, "fake.pdf", "not a pdf".getBytes()).andExpect(status().isBadRequest());
        upload(owner, "letter.docx", new byte[]{'P', 'K', 3, 4, 0, 0}).andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value(org.hamcrest.Matchers.containsString("wordprocessingml")));
        String pdf = fileId(upload(owner, "a.pdf", "%PDF-1.7 x".getBytes()));
        // another sender cannot use it, and a removed file is gone
        String body = "{\"employeeId\":%s,\"subject\":\"Hi\",\"body\":\"Text\",\"attachments\":[\"%s\"]}";
        mockMvc.perform(post("/api/mail/send").header("Authorization", auth(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(ravi, pdf))).andExpect(status().isBadRequest());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/mail/files/" + pdf)
                .header("Authorization", auth(owner))).andExpect(status().isOk());
        mockMvc.perform(post("/api/mail/send").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(ravi, pdf))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("An attached file is no longer")));
        mockMvc.perform(post("/api/mail/send").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":%s,\"subject\":\"Hi\",\"body\":\"Text\",\"cc\":[\"not-an-address\"]}".formatted(ravi)))
                .andExpect(status().isBadRequest());
        assertThat(mail.sent).isEmpty();
    }

    @Test
    void problemsComeBackPerPerson() throws Exception {
        send(owner, noEmail, "Hello", "", "Text").andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(false))
                .andExpect(jsonPath("$.error").value("No office email on record"));
        send(owner, bounce, "Hello", "", "Text").andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(false))
                .andExpect(jsonPath("$.error").value("550 5.1.1 Mailbox unavailable"));
        assertThat(mail.sent).isEmpty();
    }

    @Test
    void staffMailTicksAndThePickLimit() throws Exception {
        try {
            // Staff mail with Choose people, but not Send by email
            grant(anu, "[\"5000\",\"5004\"]");
            send(employee, ravi, "Hello", "", "Text").andExpect(status().isForbidden());

            grant(anu, "[\"5000\",\"5001\",\"5004\"]");
            // no Add picture / Attach files: no files on the mail
            upload(employee, "a.pdf", "%PDF-1.7 x".getBytes()).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/mail/send").header("Authorization", auth(employee)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"employeeId\":%s,\"subject\":\"Hi\",\"body\":\"Text\",\"attachments\":[\"x\"]}".formatted(ravi)))
                    .andExpect(status().isForbidden());
            // the limits need Staff email config
            limits(employee, dw, 2, null).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/mail/config").header("Authorization", auth(employee))).andExpect(status().isForbidden());
            // a brand admin sets their own brand's, not another's or the group's
            limits(admin, null, 2, null).andExpect(status().isForbidden());
            limits(admin, dw, 2, null).andExpect(status().isOk());
            limits(owner, dw, 0, null).andExpect(status().isBadRequest());
            // the person sees only their own brand's
            mockMvc.perform(get("/api/mail/rules").header("Authorization", auth(employee)))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].brandCode").value("DW"))
                    .andExpect(jsonPath("$[0].pickLimit").value(2));
            send(employee, ravi, "Hello", "", "Text").andExpect(status().isOk());
            send(employee, bounce, "Hello", "", "Text").andExpect(status().isOk());
            // a third person in the same send is too many; the same people again is fine
            send(employee, noEmail, "Hello", "", "Text").andExpect(status().isConflict());
            send(employee, ravi, "Hello", "", "Text").andExpect(status().isOk());
            // the owner (every tick) is not limited
            send(owner, noEmail, "Hello", "", "Text").andExpect(status().isOk());
        } finally {
            grant(anu, "[]");
            limits(owner, dw, 5, null);
        }
    }

    @Test
    void theDailyLimitOfABrandCountsWhatWentOutAndBindsEveryone() throws Exception {
        int before = sentToday();
        try {
            // room for one more today
            limits(owner, dw, 5, before + 1).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.brandId == %d)].leftToday".formatted(dw)).value(hasItem(1)));
            // not sent: not counted
            send(owner, noEmail, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(false));
            send(owner, bounce, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(false));
            assertThat(sentToday()).isEqualTo(before);

            send(owner, ravi, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(true));
            assertThat(sentToday()).isEqualTo(before + 1);
            // the limit is reached, for the owner too; nothing goes out, and it stays counted once
            mail.sent.clear();
            send(owner, anu, "Hello", "", "Text").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Today's staff email limit for DW is reached")));
            assertThat(mail.sent).isEmpty();
            assertThat(sentToday()).isEqualTo(before + 1);
            // raised: it goes
            limits(owner, dw, 5, before + 2).andExpect(status().isOk());
            send(owner, anu, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(true));
        } finally {
            limits(owner, dw, 5, null);
        }
    }

    @Test
    void theOfficeCopyIsSetPerBrandForEachKindOfMail() throws Exception {
        String customer = idOf(mockMvc.perform(post("/api/trade/customers").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"Copy Home\",\"phone\":\"9847055555\",\"email\":\"copy@example.com\"}".formatted(dw))));
        try {
            limitsAt("/api/mail/config", owner, dw, 5, null, "hr@dw.example").andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.brandId == %d)].alwaysCc".formatted(dw)).value(hasItem("hr@dw.example")));
            limitsAt("/api/trade/mail/config", owner, dw, 5, null, "sales@dw.example").andExpect(status().isOk());
            limitsAt("/api/mail/config", owner, dw, 5, null, "not-an-address").andExpect(status().isBadRequest());

            send(owner, ravi, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(true));
            assertThat(mail.sent.getLast().getRecipients(Message.RecipientType.CC)).extracting(Address::toString).containsExactly("hr@dw.example");
            tradeSend(owner, "CUSTOMER", customer).andExpect(jsonPath("$.sent").value(true));
            assertThat(mail.sent.getLast().getRecipients(Message.RecipientType.CC)).extracting(Address::toString).containsExactly("sales@dw.example");

            // cleared: the default office address again
            limitsAt("/api/trade/mail/config", owner, dw, 5, null, "").andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.brandId == %d)].alwaysCc".formatted(dw)).value(hasItem("info.amsidel@gmail.com")))
                    .andExpect(jsonPath("$[?(@.brandId == %d)].ccOverride".formatted(dw)).value(hasItem(org.hamcrest.Matchers.nullValue())));
            tradeSend(owner, "CUSTOMER", customer).andExpect(jsonPath("$.sent").value(true));
            assertThat(mail.sent.getLast().getRecipients(Message.RecipientType.CC)).extracting(Address::toString).containsExactly("info.amsidel@gmail.com");
        } finally {
            limitsAt("/api/mail/config", owner, dw, 5, null, null);
            limitsAt("/api/trade/mail/config", owner, dw, 5, null, null);
        }
    }

    @Test
    void customerAndSupplierMailHasItsOwnTicksLimitsAndCount() throws Exception {
        String customer = idOf(mockMvc.perform(post("/api/trade/customers").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"Tick Home\",\"phone\":\"9847066666\",\"email\":\"tick@example.com\"}".formatted(dw))));
        String other = idOf(mockMvc.perform(post("/api/trade/customers").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"Other Home\",\"phone\":\"9847077777\",\"email\":\"other@example.com\"}".formatted(dw))));
        String supplier = idOf(mockMvc.perform(post("/api/trade/suppliers").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"Tick Frames\",\"phone\":\"9847088888\",\"email\":\"frames@example.com\"}".formatted(dw))));
        try {
            // the menu alone sends nothing
            grant(anu, "[\"11700\"]");
            tradeSend(employee, "CUSTOMER", customer).andExpect(status().isForbidden());
            // by email, to all customers: customers yes, suppliers no; no files; no config
            grant(anu, "[\"11700\",\"13101\",\"13103\"]");
            tradeSend(employee, "CUSTOMER", customer).andExpect(jsonPath("$.sent").value(true));
            tradeSend(employee, "SUPPLIER", supplier).andExpect(status().isForbidden());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/trade/mail/files")
                            .file(new org.springframework.mock.web.MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.7 x".getBytes()))
                            .header("Authorization", auth(employee)))
                    .andExpect(status().isForbidden());
            limitsAt("/api/trade/mail/config", employee, dw, 2, null, null).andExpect(status().isForbidden());
            // Choose: at most the brand's limit
            grant(anu, "[\"11700\",\"13101\",\"13105\"]");
            limitsAt("/api/trade/mail/config", owner, dw, 2, null, null).andExpect(status().isOk());
            mockMvc.perform(get("/api/trade/mail/rules").header("Authorization", auth(employee)))
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].pickLimit").value(2));
            tradeSend(employee, "CUSTOMER", customer).andExpect(status().isOk());
            tradeSend(employee, "SUPPLIER", supplier).andExpect(status().isOk());
            tradeSend(employee, "CUSTOMER", other).andExpect(status().isConflict());

            // its own daily count, apart from staff email
            int staffBefore = sentToday();
            List<Integer> counted = JsonPath.read(body(mockMvc.perform(get("/api/trade/mail/config").header("Authorization", auth(owner)))),
                    "$[?(@.brandId == %d)].sentToday".formatted(dw));
            limitsAt("/api/trade/mail/config", owner, dw, 5, counted.get(0) + 1, null).andExpect(status().isOk());
            tradeSend(owner, "CUSTOMER", other).andExpect(jsonPath("$.sent").value(true));
            tradeSend(owner, "CUSTOMER", other).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("Today's customer & supplier email limit for DW")));
            assertThat(sentToday()).isEqualTo(staffBefore);
            send(owner, ravi, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(true));
        } finally {
            grant(anu, "[]");
            limitsAt("/api/trade/mail/config", owner, dw, 5, null, null);
        }
    }

    private ResultActions tradeSend(String token, String party, String id) throws Exception {
        return mockMvc.perform(post("/api/trade/mail/send").header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"party\":\"%s\",\"partyId\":%s,\"subject\":\"Hi\",\"body\":\"Hello\"}".formatted(party, id)));
    }

    private ResultActions limitsAt(String api, String token, Long brandId, int pickLimit, Integer dailyLimit, String cc) throws Exception {
        String copy = cc == null ? "null" : "\"" + cc + "\"";
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(api)
                .header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%s,\"pickLimit\":%d,\"dailyLimit\":%s,\"alwaysCc\":%s}".formatted(brandId, pickLimit, dailyLimit, copy)));
    }

    @Test
    void aBrandSendsThroughItsOwnBrevoAccount() throws Exception {
        String customer = idOf(mockMvc.perform(post("/api/trade/customers").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"Brevo Home\",\"phone\":\"9847099999\",\"email\":\"brevo@example.com\"}".formatted(dw))));
        String account = "{\"brandId\":%d,\"smtpLogin\":\"dw@smtp-brevo.com\",\"smtpKey\":%s,\"fromEmail\":\"mail@dw.example\",\"fromName\":\"Amsidel DW\"}";
        try {
            // the first save needs the key; only those with the config tick, for their own brand
            brevo(owner, "/api/mail", account.formatted(dw, "\"\"")).andExpect(status().isBadRequest());
            brevo(employee, "/api/mail", account.formatted(dw, "\"secret-1\"")).andExpect(status().isForbidden());
            String saved = body(brevo(owner, "/api/mail", account.formatted(dw, "\"secret-1\"")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.brandId == %d)].ownAccount".formatted(dw)).value(hasItem(true)))
                    .andExpect(jsonPath("$[?(@.brandId == %d)].accountLogin".formatted(dw)).value(hasItem("dw@smtp-brevo.com"))));
            assertThat(saved).doesNotContain("secret-1");
            mockMvc.perform(get("/api/mail/config").header("Authorization", auth(owner)))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-1"));
            // those sending see where it comes from, not the login
            mockMvc.perform(get("/api/mail/rules").header("Authorization", auth(owner)))
                    .andExpect(jsonPath("$[?(@.brandId == %d)].accountFrom".formatted(dw)).value(hasItem("mail@dw.example")))
                    .andExpect(jsonPath("$[?(@.brandId == %d)].accountLogin".formatted(dw)).value(hasItem(org.hamcrest.Matchers.nullValue())));

            // staff and customer mail go through it, from its address; the default account sends nothing
            send(owner, ravi, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(true));
            tradeSend(owner, "CUSTOMER", customer).andExpect(jsonPath("$.sent").value(true));
            List<MimeMessage> viaBrand = brandSenders.byLogin.get("dw@smtp-brevo.com").sent;
            assertThat(viaBrand).hasSize(2);
            assertThat(viaBrand.get(0).getFrom()[0].toString()).contains("mail@dw.example").contains("Amsidel DW");
            assertThat(mail.sent).isEmpty();

            // a blank key keeps the saved one; the sign-in test says what Brevo answered, and sends nothing
            brevo(owner, "/api/trade/mail", account.formatted(dw, "\"\"")).andExpect(status().isOk());
            brevoTest(owner, dw).andExpect(jsonPath("$.ok").value(true));
            brevo(owner, "/api/mail", account.formatted(dw, "\"wrong\"")).andExpect(status().isOk());
            brevoTest(owner, dw).andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.message").value("Brevo refused the login or SMTP key"));
        } finally {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/mail/config/brevo")
                    .param("brandId", String.valueOf(dw)).header("Authorization", auth(owner))).andExpect(status().isOk());
        }
        // removed: back to the default account
        send(owner, ravi, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(true));
        assertThat(mail.sent).hasSize(1);
        assertThat(mail.sent.getFirst().getFrom()[0].toString()).contains("staff@amsidel.com");
        brevoTest(owner, dw).andExpect(status().isBadRequest());
    }

    private ResultActions brevo(String token, String api, String json) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(api + "/config/brevo")
                .header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions brevoTest(String token, long brandId) throws Exception {
        return mockMvc.perform(post("/api/mail/config/brevo/test").param("brandId", String.valueOf(brandId)).header("Authorization", auth(token)));
    }

    @Test
    void theHeaderAndFooterOfTheMailAreSetPerBrandForEachKindOfMail() throws Exception {
        String customer = idOf(mockMvc.perform(post("/api/trade/customers").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%d,\"name\":\"Look Home\",\"phone\":\"9847010101\",\"email\":\"look@example.com\"}".formatted(dw))));
        // the defaults
        send(owner, ravi, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(true));
        assertThat(mail.sent.getLast().getContent()).isNotNull();
        assertThat(html(mail.sent.getLast())).contains(">AMSIDEL<").contains("Sent from the Amsidel Company Portal");
        tradeSend(owner, "CUSTOMER", customer).andExpect(jsonPath("$.sent").value(true));
        assertThat(html(mail.sent.getLast())).contains(">AMSIDEL<").contains("Sent by ");
        String look = "{\"brandId\":%d,\"pickLimit\":5,\"mailHeader\":\"%s\",\"mailFooter\":\"%s\"}";
        try {
            // only those with the config tick
            lookAt("/api/mail/config", employee, look.formatted(dw, "X", "Y")).andExpect(status().isForbidden());
            lookAt("/api/mail/config", owner, look.formatted(dw, "D&W <b>HR</b>", "Sent by HR")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.brandId == %d)].mailHeader".formatted(dw)).value(hasItem("D&W <b>HR</b>")))
                    .andExpect(jsonPath("$[?(@.brandId == %d)].defaultHeader".formatted(dw)).value(hasItem("AMSIDEL")));
            lookAt("/api/trade/mail/config", owner, look.formatted(dw, "DOORS", "Doors and windows, Kochi")).andExpect(status().isOk());
            lookAt("/api/mail/config", owner, look.formatted(dw, "x".repeat(61), "")).andExpect(status().isBadRequest());

            send(owner, ravi, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(true));
            // shown as written, never as HTML
            assertThat(html(mail.sent.getLast())).contains("D&amp;W &lt;b&gt;HR&lt;/b&gt;").contains("Sent by HR")
                    .doesNotContain("<b>HR</b>").doesNotContain(">AMSIDEL<").doesNotContain("Company Portal");
            tradeSend(owner, "CUSTOMER", customer).andExpect(jsonPath("$.sent").value(true));
            assertThat(html(mail.sent.getLast())).contains(">DOORS<").contains("Doors and windows, Kochi").doesNotContain("HR");
        } finally {
            limitsAt("/api/mail/config", owner, dw, 5, null, null);
            limitsAt("/api/trade/mail/config", owner, dw, 5, null, null);
        }
        // cleared: the defaults again
        send(owner, ravi, "Hello", "", "Text").andExpect(jsonPath("$.sent").value(true));
        assertThat(html(mail.sent.getLast())).contains(">AMSIDEL<");
    }

    private ResultActions lookAt(String api, String token, String json) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(api)
                .header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** The whole message as text, HTML part included (decoded). */
    private static String html(MimeMessage message) throws Exception {
        return collect(message.getContent());
    }

    private static String collect(Object part) throws Exception {
        if (part instanceof jakarta.mail.Multipart m) {
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < m.getCount(); i++) {
                all.append(collect(m.getBodyPart(i).getContent()));
            }
            return all.toString();
        }
        return part instanceof String text ? text : "";
    }

    private int sentToday() throws Exception {
        List<Integer> sent = JsonPath.read(body(mockMvc.perform(get("/api/mail/config").header("Authorization", auth(owner)))),
                "$[?(@.brandId == %d)].sentToday".formatted(dw));
        return sent.get(0);
    }

    private void grant(String employeeId, String checkpoints) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/menu-access/EMPLOYEE/" + employeeId)
                        .header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkpoints\":" + checkpoints + "}"))
                .andExpect(status().isOk());
    }

    private ResultActions limits(String token, Long brandId, int pickLimit, Integer dailyLimit) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/mail/config")
                .header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\":%s,\"pickLimit\":%d,\"dailyLimit\":%s}".formatted(brandId, pickLimit, dailyLimit)));
    }

    @Test
    void onlyOwnersAndAdminsSendMail() throws Exception {
        send(employee, ravi, "Hello", "", "Text").andExpect(status().isForbidden());
        send(owner, ravi, "", "", "Text").andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/mail/setup").header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.from").value("staff@amsidel.com"));
    }

    /** A 1x1 PNG. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    private ResultActions upload(String token, String name, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/mail/files").file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .header("Authorization", auth(token)));
    }

    private static String fileId(ResultActions result) throws Exception {
        return JsonPath.read(body(result), "$.id");
    }

    /** File names of the attachments. */
    private static void parts(Object content, List<String> names) throws Exception {
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                var part = multipart.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                    names.add(part.getFileName());
                }
                parts(part.getContent(), names);
            }
        }
    }

    private static String text(MimeMessage message) throws Exception {
        StringBuilder out = new StringBuilder();
        collect(message.getContent(), out);
        return out.toString();
    }

    private static void collect(Object content, StringBuilder out) throws Exception {
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i).getContent(), out);
            }
        } else {
            out.append(content);
        }
    }

    private ResultActions send(String token, String employeeId, String subject, String greeting, String text) throws Exception {
        String json = """
                {"employeeId":%s,"subject":"%s","greeting":"%s","body":"%s"}
                """.formatted(employeeId, subject, greeting, text.replace("\n", "\\n"));
        return mockMvc.perform(post("/api/mail/send").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String create(String name, String userId, String email, long brand) throws Exception {
        return create(name, userId, email, brand, "EMPLOYEE");
    }

    private String create(String name, String userId, String email, long brand, String role) throws Exception {
        String json = """
                {"name":"%s","userId":"%s","password":"ab@123","role":"%s","brandId":%d,
                 "companyEmail":%s,"phone":"+91 98470 00000","joinedDate":"2026-01-01"}
                """.formatted(name, userId, role, brand, email == null ? "null" : "\"" + email + "\"");
        return idOf(mockMvc.perform(post("/api/employees").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated()));
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

    private static String idOf(ResultActions result) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), "$.id"));
    }

    private static String auth(String token) {
        return "asd " + token;
    }
}
