package com.amsidel.backend.service;

import com.amsidel.backend.dto.MailResult;
import com.amsidel.backend.dto.MailSendRequest;
import com.amsidel.backend.dto.MailSetup;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Personal mail from owners and admins to employees' office addresses: the same text for everyone, but each
 * person gets their own mail addressed to them ("Dear Anu," or "Dear All,"), with optional CC/BCC, attachments and
 * pictures inside the message (see MailFileStore). Nothing is stored; each send returns whether it went out, and
 * the portal shows that on screen.
 * No database connection is held while talking to the mail server.
 */
@Slf4j
@Service
public class MailService {

    private static final String NAME = "{name}";
    /** Where a picture goes in the text: [[image 1]]. */
    private static final Pattern PICTURE = Pattern.compile("\\[\\[image (\\d{1,2})]]");
    /** Inside the mail's 640px card with 24px padding. */
    private static final int PICTURE_WIDTH = 592;

    private final ObjectProvider<JavaMailSender> mailSender;
    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;
    private final MailFileStore files;
    private final String host;
    private final boolean signInReady;
    private final String from;
    private final String fromName;

    public MailService(ObjectProvider<JavaMailSender> mailSender, EmployeeService employeeService,
                       EmployeeRepository employeeRepository, MailFileStore files,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${spring.mail.password:}") String password,
                       @Value("${app.mail.from:}") String from,
                       @Value("${app.mail.from-name:Amsidel Group}") String fromName) {
        this.mailSender = mailSender;
        this.employeeService = employeeService;
        this.employeeRepository = employeeRepository;
        this.files = files;
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
        // a missing or too large file is a problem with the message, not with this person: 400 for the whole send
        List<Picture> pictures = pictures(me, request);
        List<MailFileStore.Stored> attached = attachments(me, request);
        long total = pictures.stream().mapToLong(p -> p.file().bytes().length).sum()
                + attached.stream().mapToLong(f -> f.bytes().length).sum();
        if (total > MailFileStore.MAX_TOTAL) {
            throw ApiException.badRequest("Attachments and pictures together must be 14 MB or smaller");
        }
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
            for (String cc : addresses(request.getCc())) {
                mail.addCc(cc);
            }
            for (String bcc : addresses(request.getBcc())) {
                mail.addBcc(bcc);
            }
            if (sender != null && StringUtils.hasText(sender.getCompanyEmail())) {
                mail.setReplyTo(sender.getCompanyEmail().trim());
            }
            mail.setSubject(subject);
            mail.setText(plain(greeting, text, signedBy, company, pictures), html(greeting, text, signedBy, company, pictures));
            // after setText: the pictures belong to the HTML part
            for (Picture p : pictures) {
                mail.addInline(p.cid(), new ByteArrayResource(p.file().bytes()), p.file().contentType());
            }
            for (MailFileStore.Stored f : attached) {
                mail.addAttachment(f.name(), new ByteArrayResource(f.bytes()), f.contentType());
            }
            mailSender.getObject().send(message);
            log.info("Staff mail '{}' sent to {} by {}", subject, to.getEmpNumber(), me.userId());
            return MailResult.sent(to.getId(), name, email);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.warn("Staff mail '{}' to {} failed: {}", subject, to.getEmpNumber(), e.getMessage());
            return MailResult.failed(to.getId(), name, email, reason(e));
        }
    }

    public boolean ready() {
        return StringUtils.hasText(host) && StringUtils.hasText(from) && signInReady && mailSender.getIfAvailable() != null;
    }

    private static String fill(String template, String name) {
        return template.replace(NAME, name);
    }

    /** A picture inside the message: at [[image n]] in the text, or after the text when not marked. */
    private record Picture(int number, MailFileStore.Stored file, String caption) {
        String cid() {
            return "picture" + number;
        }
    }

    private List<Picture> pictures(AuthUser me, MailSendRequest request) {
        List<MailSendRequest.Picture> list = request.getImages() == null ? List.of() : request.getImages();
        List<Picture> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MailFileStore.Stored f = files.get(me, list.get(i).getFileId());
            if (!f.inlineable()) {
                throw ApiException.badRequest(f.name() + ": only JPG, PNG and GIF pictures can go inside the message; attach it instead");
            }
            String caption = list.get(i).getCaption() == null ? "" : list.get(i).getCaption().strip();
            out.add(new Picture(i + 1, f, caption));
        }
        return out;
    }

    private List<MailFileStore.Stored> attachments(AuthUser me, MailSendRequest request) {
        List<String> ids = request.getAttachments() == null ? List.of() : request.getAttachments();
        return ids.stream().distinct().map(id -> files.get(me, id)).toList();
    }

    /** Trimmed, without repeats (ignoring case). */
    private static List<String> addresses(List<String> list) {
        if (list == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String a : list) {
            String address = a.strip();
            if (seen.add(address.toLowerCase(Locale.ROOT))) {
                out.add(address);
            }
        }
        return out;
    }

    private static String plain(String greeting, String text, String signedBy, String company, List<Picture> pictures) {
        String body = place(text, pictures, p -> "[Picture" + (p.caption().isEmpty() ? "" : ": " + p.caption()) + "]", "\n\n");
        return "Dear " + greeting + ",\n\n" + body + "\n\nRegards,\n" + signedBy + "\n" + company + "\n";
    }

    /** Each [[image n]] replaced by the picture; pictures not marked go after the text (unknown numbers drop out). */
    private static String place(String text, List<Picture> pictures, java.util.function.Function<Picture, String> show,
                                String before) {
        Set<Integer> placed = new LinkedHashSet<>();
        Matcher m = PICTURE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            Picture p = n >= 1 && n <= pictures.size() ? pictures.get(n - 1) : null;
            if (p != null) {
                placed.add(n);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(p == null ? "" : show.apply(p)));
        }
        m.appendTail(out);
        for (Picture p : pictures) {
            if (!placed.contains(p.number())) {
                out.append(before).append(show.apply(p));
            }
        }
        return out.toString();
    }

    /** A block of its own, on one line (line breaks in the text become <br>). */
    private static String pictureHtml(Picture p) {
        int width = Math.min(PICTURE_WIDTH, p.file().width());
        String alt = p.caption().isEmpty() ? "Picture " + p.number() : p.caption();
        String caption = p.caption().isEmpty() ? ""
                : "<div style=\"margin-top:6px;font-size:13px;line-height:1.4;color:#6f6a60\">" + escape(p.caption()) + "</div>";
        return "<div style=\"margin:16px 0;text-align:center\"><img src=\"cid:" + p.cid() + "\" width=\"" + width
                + "\" alt=\"" + escape(alt) + "\" style=\"display:block;margin:0 auto;max-width:100%;height:auto;border:0;border-radius:6px\">"
                + caption + "</div>";
    }

    private static String html(String greeting, String text, String signedBy, String company, List<Picture> pictures) {
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
                """.formatted(escape(greeting), place(escape(text), pictures, MailService::pictureHtml, "").replace("\n", "<br>"),
                escape(signedBy), escape(company));
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
