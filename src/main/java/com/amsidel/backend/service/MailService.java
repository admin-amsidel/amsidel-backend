package com.amsidel.backend.service;

import com.amsidel.backend.dto.MailContent;
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
import org.springframework.transaction.annotation.Transactional;
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
 * pictures inside the message (see MailFileStore), placed left, centre or right in three sizes. The text may have
 * **bold** words and "# " heading lines, and ends with the sender's own signature. Nothing about the mail is stored;
 * each send returns whether it went out, and the portal shows that on screen.
 * Customer and supplier mail (TradeMailService) goes out the same way, through deliver(). Every mail is also copied
 * (CC) to the office address (app.mail.always-cc), so the office keeps track of what is sent.
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
    /** **bold** (not across lines, no space just inside the stars). */
    private static final Pattern BOLD = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*");
    /** A heading line: "# Office timings". */
    private static final Pattern HEADING = Pattern.compile("^#{1,3}\\s+(.+)$");
    // the light blue look
    private static final String BLUE = "#1d5fa3";
    private static final String INK = "#1f2a37";
    private static final String MUTED = "#5b6b7d";

    private final ObjectProvider<JavaMailSender> mailSender;
    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;
    private final MailFileStore files;
    private final String host;
    private final boolean signInReady;
    private final String from;
    private final String fromName;
    /** Copied on every mail; empty = none. */
    private final String alwaysCc;

    public MailService(ObjectProvider<JavaMailSender> mailSender, EmployeeService employeeService,
                       EmployeeRepository employeeRepository, MailFileStore files,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${spring.mail.password:}") String password,
                       @Value("${app.mail.from:}") String from,
                       @Value("${app.mail.from-name:Amsidel Group}") String fromName,
                       @Value("${app.mail.always-cc:info.amsidel@gmail.com}") String alwaysCc) {
        this.mailSender = mailSender;
        this.employeeService = employeeService;
        this.employeeRepository = employeeRepository;
        this.files = files;
        this.host = host;
        // a mail server that needs a sign-in (Gmail) is not ready until its password is given
        this.signInReady = !StringUtils.hasText(username) || StringUtils.hasText(password);
        this.from = StringUtils.hasText(from) ? from.trim() : username.trim();
        this.fromName = fromName;
        this.alwaysCc = alwaysCc == null ? "" : alwaysCc.trim();
    }

    public MailSetup setup() {
        return new MailSetup(ready(), from, fromName, alwaysCc);
    }

    /** The office address copied on every mail (staff, customer and supplier mail, website enquiries); empty = none. */
    public String alwaysCc() {
        return alwaysCc;
    }

    /** My sign-off: saved = false when it is the standard one. */
    public record SignatureView(String signature, boolean saved, String standard) {
    }

    @Transactional(readOnly = true)
    public SignatureView signature(AuthUser me) {
        Employee e = employeeRepository.findWithBrandById(me.id()).orElse(null);
        String standard = standardSignature(e);
        String saved = e == null ? null : e.getMailSignature();
        return new SignatureView(StringUtils.hasText(saved) ? saved : standard, StringUtils.hasText(saved), standard);
    }

    @Transactional
    public SignatureView saveSignature(AuthUser me, String signature) {
        Employee e = employeeRepository.findById(me.id()).orElseThrow(() -> ApiException.badRequest("Sign in again"));
        String text = signature == null ? "" : signature.replace("\r\n", "\n").strip();
        e.setMailSignature(text.isEmpty() || text.equals(standardSignature(employeeRepository.findWithBrandById(me.id()).orElse(null)))
                ? null : text);
        employeeRepository.save(e);
        return signature(me);
    }

    /** Regards, the name in bold, the designation and the brand. */
    private String standardSignature(Employee e) {
        if (e == null) {
            return "Regards,\n**" + fromName + "**";
        }
        StringBuilder s = new StringBuilder("Regards,\n**").append(e.getName().trim()).append("**");
        if (e.getDesignation() != null) {
            s.append("\n").append(e.getDesignation().getName());
        }
        s.append("\n").append(e.getBrand() == null ? fromName : e.getBrand().getName());
        return s.toString();
    }

    /** Sends one personal mail; problems with this one person come back as a failed result, not an error. */
    public MailResult send(AuthUser me, MailSendRequest request) {
        Employee to = employeeService.findVisible(me, request.getEmployeeId());
        String name = to.getName().trim();
        String email = to.getCompanyEmail() == null ? null : to.getCompanyEmail().trim();
        if (!to.isActive()) {
            checkFiles(me, request);
            return MailResult.failed(to.getId(), name, email, "Not an active employee");
        }
        return deliver(me, request, to.getId(), name, email, "No office email on record", "Staff mail", to.getEmpNumber(),
                "Sent from the Amsidel Company Portal");
    }

    /** The files of the message exist and are not too large together; 400 for the whole send when not. */
    private void checkFiles(AuthUser me, MailContent request) {
        List<Picture> pictures = pictures(me, request);
        List<MailFileStore.Stored> attached = attachments(me, request);
        long total = pictures.stream().mapToLong(p -> p.file().bytes().length).sum()
                + attached.stream().mapToLong(f -> f.bytes().length).sum();
        if (total > MailFileStore.MAX_TOTAL) {
            throw ApiException.badRequest("Attachments and pictures together must be 14 MB or smaller");
        }
    }

    /**
     * Sends one personal mail to one person (an employee, a customer or a supplier) in the portal's design: their name
     * after "Dear", the sender's signature, the pictures and attachments, the CC/BCC, and always a copy to the office
     * address. noEmail: the reason shown when the person has no address; kind and who: for the log; footer: the line
     * under the mail.
     */
    public MailResult deliver(AuthUser me, MailContent request, Long id, String name, String email, String noEmail,
                              String kind, String who, String footer) {
        // a missing or too large file is a problem with the message, not with this person: 400 for the whole send
        List<Picture> pictures = pictures(me, request);
        List<MailFileStore.Stored> attached = attachments(me, request);
        long total = pictures.stream().mapToLong(p -> p.file().bytes().length).sum()
                + attached.stream().mapToLong(f -> f.bytes().length).sum();
        if (total > MailFileStore.MAX_TOTAL) {
            throw ApiException.badRequest("Attachments and pictures together must be 14 MB or smaller");
        }
        if (!StringUtils.hasText(email)) {
            return MailResult.failed(id, name, null, noEmail);
        }
        email = email.trim();
        if (!ready()) {
            return MailResult.failed(id, name, email, "Mail is not set up on the server");
        }

        Employee sender = employeeRepository.findWithBrandById(me.id()).orElse(null);
        String subject = fill(request.getSubject(), name).trim();
        String greeting = StringUtils.hasText(request.getGreeting()) ? fill(request.getGreeting(), name).trim() : name;
        String text = fill(request.getBody(), name).strip();
        String signature = StringUtils.hasText(request.getSignature()) ? request.getSignature().strip()
                : sender != null && StringUtils.hasText(sender.getMailSignature()) ? sender.getMailSignature()
                : standardSignature(sender);
        signature = fill(signature, name);
        try {
            MimeMessage message = mailSender.getObject().createMimeMessage();
            MimeMessageHelper mail = new MimeMessageHelper(message, true, "UTF-8");
            mail.setFrom(from, fromName);
            mail.setTo(email);
            for (String cc : copies(request.getCc(), email)) {
                mail.addCc(cc);
            }
            for (String bcc : addresses(request.getBcc())) {
                mail.addBcc(bcc);
            }
            if (sender != null && StringUtils.hasText(sender.getCompanyEmail())) {
                mail.setReplyTo(sender.getCompanyEmail().trim());
            }
            mail.setSubject(subject);
            mail.setText(plain(greeting, text, signature, pictures), html(greeting, text, signature, pictures, footer));
            // after setText: the pictures belong to the HTML part
            for (Picture p : pictures) {
                mail.addInline(p.cid(), new ByteArrayResource(p.file().bytes()), p.file().contentType());
            }
            for (MailFileStore.Stored f : attached) {
                mail.addAttachment(f.name(), new ByteArrayResource(f.bytes()), f.contentType());
            }
            mailSender.getObject().send(message);
            log.info("{} '{}' sent to {} by {}", kind, subject, who, me.userId());
            return MailResult.sent(id, name, email);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.warn("{} '{}' to {} failed: {}", kind, subject, who, e.getMessage());
            return MailResult.failed(id, name, email, reason(e));
        }
    }

    /** The CC addresses given, plus the office address (unless the mail already goes to it, or it is given already). */
    List<String> copies(List<String> cc, String to) {
        List<String> out = new ArrayList<>(addresses(cc));
        if (StringUtils.hasText(alwaysCc) && !alwaysCc.equalsIgnoreCase(to)
                && out.stream().noneMatch(a -> a.equalsIgnoreCase(alwaysCc))) {
            out.add(alwaysCc);
        }
        return out;
    }

    public boolean ready() {
        return StringUtils.hasText(host) && StringUtils.hasText(from) && signInReady && mailSender.getIfAvailable() != null;
    }

    private static String fill(String template, String name) {
        return template.replace(NAME, name);
    }

    /** A picture inside the message: at [[image n]] in the text, or after the text when not marked. */
    private record Picture(int number, MailFileStore.Stored file, String caption, String align, int maxWidth) {
        String cid() {
            return "picture" + number;
        }
    }

    private List<Picture> pictures(AuthUser me, MailContent request) {
        List<MailContent.Picture> list = request.getImages() == null ? List.of() : request.getImages();
        List<Picture> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MailFileStore.Stored f = files.get(me, list.get(i).getFileId());
            if (!f.inlineable()) {
                throw ApiException.badRequest(f.name() + ": only JPG, PNG and GIF pictures can go inside the message; attach it instead");
            }
            MailContent.Picture in = list.get(i);
            String caption = in.getCaption() == null ? "" : in.getCaption().strip();
            String align = in.getAlign() == null ? "center" : in.getAlign().toLowerCase(Locale.ROOT);
            int max = "SMALL".equals(in.getSize()) ? 200 : "MEDIUM".equals(in.getSize()) ? 360 : PICTURE_WIDTH;
            out.add(new Picture(i + 1, f, caption, align, max));
        }
        return out;
    }

    private List<MailFileStore.Stored> attachments(AuthUser me, MailContent request) {
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

    private static String plain(String greeting, String text, String signature, List<Picture> pictures) {
        String body = place(text, pictures, p -> "[Picture" + (p.caption().isEmpty() ? "" : ": " + p.caption()) + "]", "\n\n");
        return "Dear " + greeting + ",\n\n" + plainText(body) + "\n\n" + plainText(signature) + "\n";
    }

    /** Without the marks: **bold** -> bold, "# Heading" -> HEADING. */
    static String plainText(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            Matcher h = HEADING.matcher(line);
            String l = h.matches() ? h.group(1).toUpperCase(Locale.ROOT) : line;
            out.append(BOLD.matcher(l).replaceAll("$1")).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    /** Escaped text with **bold** as bold and "# " lines as headings; other line breaks stay. */
    static String rich(String escaped) {
        String[] lines = escaped.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean block = true;
        for (String line : lines) {
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                out.append("<div style=\"margin:18px 0 6px;font-size:18px;line-height:1.35;font-weight:700;color:").append(BLUE)
                        .append("\">").append(BOLD.matcher(h.group(1)).replaceAll("$1")).append("</div>");
                block = true;
                continue;
            }
            if (!block) {
                out.append("<br>");
            }
            out.append(BOLD.matcher(line).replaceAll("<b>$1</b>"));
            block = line.startsWith("<div");
        }
        return out.toString();
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

    /** A block of its own, on one line: left, centre or right, at most its size's width, its description under it. */
    private static String pictureHtml(Picture p) {
        int width = Math.min(p.maxWidth(), p.file().width());
        String alt = p.caption().isEmpty() ? "Picture " + p.number() : p.caption();
        String caption = p.caption().isEmpty() ? ""
                : "<div style=\"margin-top:6px;font-size:13px;line-height:1.4;color:" + MUTED + "\">" + escape(p.caption()) + "</div>";
        return "<div align=\"" + p.align() + "\" style=\"margin:16px 0;text-align:" + p.align() + "\"><img src=\"cid:" + p.cid()
                + "\" width=\"" + width + "\" alt=\"" + escape(alt)
                + "\" style=\"display:inline-block;max-width:100%;height:auto;border:0;border-radius:6px\">" + caption + "</div>";
    }

    /** Light blue: a pale blue page, a white card with a blue band on top, dark grey text. */
    private static String html(String greeting, String text, String signature, List<Picture> pictures, String footer) {
        return """
                <!doctype html><html><body style="margin:0;padding:24px 12px;background:#eef4fb;font-family:Arial,Helvetica,sans-serif;color:%s">
                <table role="presentation" width="100%%" style="max-width:640px;margin:0 auto;background:#ffffff;border:1px solid #d6e4f5;border-radius:10px;border-collapse:separate;overflow:hidden">
                <tr><td style="background:#e3eefa;border-bottom:3px solid #2f74c0;color:%s;padding:16px 24px;font-size:14px;font-weight:700;letter-spacing:4px">AMSIDEL</td></tr>
                <tr><td style="padding:24px;font-size:15px;line-height:1.6;color:%s">
                <p style="margin:0 0 16px">Dear %s,</p>
                <div>%s</div>
                <div style="margin:28px 0 0;padding-top:14px;border-top:1px solid #e3ecf7;color:%s">%s</div>
                </td></tr>
                <tr><td style="padding:12px 24px;background:#f5f9fe;border-top:1px solid #dbe7f5;font-size:12px;color:%s">%s</td></tr>
                </table></body></html>
                """.formatted(INK, BLUE, INK, escape(greeting), rich(place(escape(text), pictures, MailService::pictureHtml, "")),
                MUTED, rich(escape(signature)), MUTED, escape(footer));
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
