/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.MailAccountRequest;
import com.amsidel.backend.entity.MailBrandAccount;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.MailBrandAccountRepository;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each brand's own Brevo account (the Email config screens): its SMTP login and key and the address its mail comes
 * from, used for both staff email and customer &amp; supplier email to that brand's people. A brand without one uses the
 * default account (spring.mail.*, app.mail.from). The server and port are the default account's: Render's free plan
 * blocks the usual mail ports, and Brevo takes the same connection on 2525. The key is saved, never sent back.
 */
@Component
public class BrandMailSenders {

    /** How one brand's mail goes out. */
    public record Route(JavaMailSender sender, String from, String fromName) {
    }

    /** The outcome of signing in to Brevo with a brand's account; nothing is sent. */
    public record TestResult(boolean ok, String message) {
    }

    private record Built(String login, String key, JavaMailSenderImpl sender) {
    }

    private final MailBrandAccountRepository accounts;
    private final String host;
    private final int port;
    private final Map<Long, Built> built = new ConcurrentHashMap<>();

    public BrandMailSenders(MailBrandAccountRepository accounts, @Value("${spring.mail.host:smtp-relay.brevo.com}") String host,
                            @Value("${spring.mail.port:2525}") int port) {
        this.accounts = accounts;
        this.host = StringUtils.hasText(host) ? host.trim() : "smtp-relay.brevo.com";
        this.port = port;
    }

    /** The brand's own way out; empty = the default account. */
    public Optional<Route> route(long brandKey) {
        return accounts.findById(brandKey).map(a -> new Route(sender(a), a.getFromEmail(), a.getFromName()));
    }

    public List<MailBrandAccount> all() {
        return accounts.findAll();
    }

    /** Saves the brand's account; a blank key keeps the saved one. */
    public void save(long brandKey, MailAccountRequest body) {
        MailBrandAccount a = accounts.findById(brandKey).orElseGet(MailBrandAccount::new);
        String key = StringUtils.hasText(body.getSmtpKey()) ? body.getSmtpKey().trim() : a.getSmtpKey();
        if (!StringUtils.hasText(key)) {
            throw ApiException.badRequest("Give the Brevo SMTP key");
        }
        a.setBrandKey(brandKey);
        a.setSmtpLogin(body.getSmtpLogin().trim());
        a.setSmtpKey(key);
        a.setFromEmail(body.getFromEmail().trim());
        a.setFromName(StringUtils.hasText(body.getFromName()) ? body.getFromName().trim() : null);
        accounts.save(a);
        built.remove(brandKey);
    }

    /** The brand goes back to the default account. */
    public void remove(long brandKey) {
        if (accounts.existsById(brandKey)) {
            accounts.deleteById(brandKey);
        }
        built.remove(brandKey);
    }

    /** Signs in to Brevo with the saved account; it cannot show whether the From address is verified there. */
    public TestResult test(long brandKey) {
        MailBrandAccount a = accounts.findById(brandKey)
                .orElseThrow(() -> ApiException.badRequest("This brand has no Brevo account of its own"));
        try {
            sender(a).testConnection();
            return new TestResult(true, "Brevo accepted the login and key");
        } catch (AuthenticationFailedException e) {
            return new TestResult(false, "Brevo refused the login or SMTP key");
        } catch (MessagingException e) {
            return new TestResult(false, "Could not reach Brevo: " + e.getMessage());
        }
    }

    private JavaMailSenderImpl sender(MailBrandAccount a) {
        Built b = built.get(a.getBrandKey());
        if (b == null || !b.login().equals(a.getSmtpLogin()) || !b.key().equals(a.getSmtpKey())) {
            b = new Built(a.getSmtpLogin(), a.getSmtpKey(), build(a.getSmtpLogin(), a.getSmtpKey()));
            built.put(a.getBrandKey(), b);
        }
        return b.sender();
    }

    /** The same connection settings as the default account (application.properties). */
    protected JavaMailSenderImpl build(String login, String key) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(host);
        s.setPort(port);
        s.setUsername(login);
        s.setPassword(key);
        s.setDefaultEncoding("UTF-8");
        Properties p = s.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.starttls.required", "true");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "20000");
        p.put("mail.smtp.writetimeout", "20000");
        return s;
    }
}
