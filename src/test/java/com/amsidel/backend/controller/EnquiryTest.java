package com.amsidel.backend.controller;

import jakarta.mail.Address;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Website enquiries: a designed mail to the enquiry inbox, open to the public but limited. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"test.context=enquiry", "spring.mail.host=localhost", "spring.mail.username=",
        "app.mail.from=staff@amsidel.com", "app.enquiry.to=enquiries@amsidel.com",
        "app.enquiry.send-in-background=false"})
class EnquiryTest {

    static class FakeMailSender extends JavaMailSenderImpl {
        final List<MimeMessage> sent = new ArrayList<>();

        @Override
        protected void doSend(MimeMessage[] messages, Object[] originals) {
            sent.addAll(List.of(messages));
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

    @BeforeEach
    void clear() {
        mail.sent.clear();
    }

    @Test
    void aQuoteRequestArrivesAsADesignedMail() throws Exception {
        enquiry("10.0.0.1", """
                {"kind":"QUOTE","headline":"Casement windows","name":"Anu <Thomas>","phone":"+91 98470 12345",
                 "email":"anu@example.com","details":[{"label":"Location","value":"Kothamangalam"},{"label":"Interested in","value":""}],
                 "message":"Need 4 windows\\nfor the new house","page":"https://www.amsidel.com/doors-windows/contact"}
                """).andExpect(status().isOk());

        assertThat(mail.sent).hasSize(1);
        MimeMessage m = mail.sent.getFirst();
        assertThat(m.getAllRecipients()).extracting(Address::toString).containsExactly("enquiries@amsidel.com");
        assertThat(m.getReplyTo()[0].toString()).contains("anu@example.com");
        assertThat(m.getSubject()).isEqualTo("New website enquiry – Casement windows (Anu <Thomas>)");
        String all = text(m);
        assertThat(all).contains("Anu &lt;Thomas&gt;").doesNotContain("<b>Anu <Thomas>")
                .contains("href=\"tel:+919847012345\"").contains("https://wa.me/919847012345")
                .contains("Need 4 windows<br>for the new house").contains("Kothamangalam")
                .contains("Phone: +91 98470 12345");
    }

    @Test
    void aChatComesWithItsTranscript() throws Exception {
        enquiry("10.0.0.2", """
                {"kind":"CHAT","name":"Ravi","phone":"9847012345","details":[{"label":"Query","value":"Price of sliding doors"}],
                 "transcript":[{"visitor":false,"text":"Hi, I am AMI"},{"visitor":true,"text":"Price of sliding doors?"}]}
                """).andExpect(status().isOk());
        MimeMessage m = mail.sent.getFirst();
        assertThat(m.getSubject()).isEqualTo("AMI chat – query from Ravi");
        assertThat(m.getReplyTo()[0].toString()).contains("staff@amsidel.com");
        assertThat(text(m)).contains("Hi, I am AMI").contains("https://wa.me/919847012345").contains("Ravi: Price of sliding doors?");
    }

    @Test
    void botsAreDroppedAndOneAddressIsLimited() throws Exception {
        String body = """
                {"kind":"SITE_VISIT","headline":"Mon, 26 Oct 2026, 10:00 AM","name":"Bot","phone":"1","website":"%s"}""";
        enquiry("10.0.0.3", body.formatted("http://spam")).andExpect(status().isOk());
        assertThat(mail.sent).isEmpty();

        for (int i = 0; i < 5; i++) {
            enquiry("10.0.0.4", body.formatted("")).andExpect(status().isOk());
        }
        enquiry("10.0.0.4", body.formatted("")).andExpect(status().isTooManyRequests());
        enquiry("10.0.0.5", body.formatted("")).andExpect(status().isOk());
        assertThat(mail.sent).hasSize(6);

        enquiry("10.0.0.6", "{\"kind\":\"QUOTE\",\"name\":\"X\",\"phone\":\"1\",\"email\":\"not-an-email\"}")
                .andExpect(status().isBadRequest());
    }

    private ResultActions enquiry(String ip, String json) throws Exception {
        return mockMvc.perform(post("/api/public/enquiry").header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON).content(json));
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
}
