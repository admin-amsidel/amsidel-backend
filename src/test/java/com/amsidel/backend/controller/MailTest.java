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

import static org.assertj.core.api.Assertions.assertThat;
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

    @TestConfiguration
    static class FakeMail {
        @Bean
        FakeMailSender javaMailSender() {
            return new FakeMailSender();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeMailSender mail;

    private String owner;
    private String employee;
    private String anu;
    private String ravi;
    private String noEmail;
    private String bounce;

    @BeforeAll
    void setUp() throws Exception {
        owner = token(changePin(token(login("owner", "Ams@2026")), "Ams@2026", "Own#2026"));
        List<Integer> brandIds = JsonPath.read(body(mockMvc.perform(get("/api/brands").header("Authorization", auth(owner)))),
                "$[?(@.code == 'DW')].id");
        long dw = brandIds.get(0);
        anu = create("Anu Thomas", "ml.anu", "anu@amsidel.com", dw);
        ravi = create("Ravi Kumar", "ml.ravi", "ravi@amsidel.com", dw);
        noEmail = create("No Mail", "ml.none", null, dw);
        bounce = create("Bounce Back", "ml.bounce", "bounce@amsidel.com", dw);
        create("Mail Admin", "ml.admin", null, dw, "ADMIN");
        employee = token(changePin(token(login("ml.anu", "ab@123")), "ab@123", "cd@456"));
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
        String admin = token(changePin(token(login("ml.admin", "ab@123")), "ab@123", "cd@456"));
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
