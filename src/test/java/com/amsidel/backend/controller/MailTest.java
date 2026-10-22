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
        assertThat(first.getAllRecipients()).extracting(Address::toString).containsExactly("anu@amsidel.com");
        assertThat(first.getSubject()).isEqualTo("New client: Kumar residence (Anu Thomas)");
        assertThat(first.getFrom()[0].toString()).contains("staff@amsidel.com");
        assertThat(text(first)).contains("Dear Anu Thomas,").contains("Thanks, Anu Thomas.");
        assertThat(text(mail.sent.get(1))).contains("Dear All,");
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
        assertThat(m.getRecipients(Message.RecipientType.CC)).extracting(Address::toString).containsExactly("hr@amsidel.com");
        assertThat(m.getRecipients(Message.RecipientType.BCC)).extracting(Address::toString).containsExactly("boss@amsidel.com");
        String all = text(m);
        assertThat(all).contains("src=\"cid:picture1\"").contains("The new office &lt;Kochi&gt;")
                .contains("[Picture: The new office <Kochi>]").doesNotContain("[[image 1]]");
        List<String> names = new ArrayList<>();
        parts(m.getContent(), names);
        assertThat(names).contains("Leave policy.pdf");
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
