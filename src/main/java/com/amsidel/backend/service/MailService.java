package com.amsidel.backend.service;

import com.amsidel.backend.dto.MailResult;
import com.amsidel.backend.dto.MailSendRequest;
import com.amsidel.backend.dto.MailSetup;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Personal mail from owners and admins to employees' office addresses: the same text for everyone, but each
 * person gets their own mail addressed to them ("Dear Anu," or "Dear All,"). Nothing is stored; each send
 * returns whether it went out, and the portal shows that on screen.
 * No database connection is held while talking to the mail server.
 */
@Slf4j
@Service
public class MailService {

    private static final String NAME = "{name}";

    private final ObjectProvider<JavaMailSender> mailSender;
    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;
    private final String host;
    private final boolean signInReady;
    private final String from;
    private final String fromName;

    public MailService(ObjectProvider<JavaMailSender> mailSender, EmployeeService employeeService,
                       EmployeeRepository employeeRepository,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${spring.mail.password:}") String password,
                       @Value("${app.mail.from:}") String from,
                       @Value("${app.mail.from-name:Amsidel Group}") String fromName) {
        this.mailSender = mailSender;
        this.employeeService = employeeService;
        this.employeeRepository = employeeRepository;
        this.host = host;
        // a mail server that needs a sign-in (Gmail) is not ready until its password is given
        this.signInReady = !StringUtils.hasText(username) || StringUtils.hasText(password);
        this.from = StringUtils.hasText(from) ? from.trim() : username.trim();
        this.fromName = fromName;
    }

    public MailSetup setup() {
        return new MailSetup(ready(), from, fromName);
    }

    /** Sends one personal mail; problems with this one person come back as a failed result, not an error. */
    public MailResult send(AuthUser me, MailSendRequest request) {
        Employee to = employeeService.findVisible(me, request.getEmployeeId());
        String name = to.getName().trim();
        String email = to.getCompanyEmail() == null ? null : to.getCompanyEmail().trim();
        if (!to.isActive()) {
            return MailResult.failed(to.getId(), name, email, "Not an active employee");
        }
        if (!StringUtils.hasText(email)) {
            return MailResult.failed(to.getId(), name, null, "No office email on record");
        }
        if (!ready()) {
            return MailResult.failed(to.getId(), name, email, "Mail is not set up on the server");
        }

        Employee sender = employeeRepository.findWithBrandById(me.id()).orElse(null);
        String subject = fill(request.getSubject(), name).trim();
        String greeting = StringUtils.hasText(request.getGreeting()) ? fill(request.getGreeting(), name).trim() : name;
        String text = fill(request.getBody(), name).strip();
        String signedBy = sender == null ? fromName : sender.getName();
        String company = sender == null || sender.getBrand() == null ? fromName : sender.getBrand().getName();
        try {
            MimeMessage message = mailSender.getObject().createMimeMessage();
            MimeMessageHelper mail = new MimeMessageHelper(message, true, "UTF-8");
            mail.setFrom(from, fromName);
            mail.setTo(email);
            if (sender != null && StringUtils.hasText(sender.getCompanyEmail())) {
                mail.setReplyTo(sender.getCompanyEmail().trim());
            }
            mail.setSubject(subject);
            mail.setText(plain(greeting, text, signedBy, company), html(greeting, text, signedBy, company));
            mailSender.getObject().send(message);
            log.info("Staff mail '{}' sent to {} by {}", subject, to.getEmpNumber(), me.userId());
            return MailResult.sent(to.getId(), name, email);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.warn("Staff mail '{}' to {} failed: {}", subject, to.getEmpNumber(), e.getMessage());
            return MailResult.failed(to.getId(), name, email, reason(e));
        }
    }

    private boolean ready() {
        return StringUtils.hasText(host) && StringUtils.hasText(from) && signInReady && mailSender.getIfAvailable() != null;
    }

    private static String fill(String template, String name) {
        return template.replace(NAME, name);
    }

    private static String plain(String greeting, String text, String signedBy, String company) {
        return "Dear " + greeting + ",\n\n" + text + "\n\nRegards,\n" + signedBy + "\n" + company + "\n";
    }

    private static String html(String greeting, String text, String signedBy, String company) {
        return """
                <!doctype html><html><body style="margin:0;padding:24px;background:#f6f3ee;font-family:Arial,Helvetica,sans-serif;color:#2b2925">
                <table role="presentation" width="100%%" style="max-width:640px;margin:0 auto;background:#ffffff;border-radius:8px;border-collapse:collapse">
                <tr><td style="background:#1c1b19;color:#ffffff;padding:16px 24px;font-size:14px;letter-spacing:3px">AMSIDEL</td></tr>
                <tr><td style="padding:24px;font-size:15px;line-height:1.6">
                <p style="margin:0 0 16px">Dear %s,</p>
                <div>%s</div>
                <p style="margin:24px 0 0">Regards,<br><b>%s</b><br><span style="color:#6f6a60">%s</span></p>
                </td></tr>
                <tr><td style="padding:12px 24px;border-top:1px solid #ece7de;font-size:12px;color:#8a8478">Sent from the Amsidel Staff Portal</td></tr>
                </table></body></html>
                """.formatted(escape(greeting), escape(text).replace("\n", "<br>"), escape(signedBy), escape(company));
    }

    private static String escape(String s) {
        return HtmlUtils.htmlEscape(s.replace("\r\n", "\n"));
    }

    /** A short reason for the portal; the full error goes to the log. */
    private static String reason(Exception e) {
        if (e instanceof MailAuthenticationException) {
            return "The mail server refused the sign-in (check the mail user name and password)";
        }
        Throwable root = e;
        if (e instanceof MailSendException send && !send.getFailedMessages().isEmpty()) {
            root = send.getFailedMessages().values().iterator().next();
        }
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = (root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage()).strip();
        if (root instanceof ConnectException || root instanceof UnknownHostException || root instanceof SocketTimeoutException) {
            message = "Cannot reach the mail server (" + message + ")";
        }
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }
}
