package com.amsidel.backend.service;

import com.amsidel.backend.dto.EnquiryRequest;
import com.amsidel.backend.dto.EnquiryRequest.Kind;
import com.amsidel.backend.exception.ApiException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Website enquiries (quote form, site-visit form, AMI chat) as a designed mail to the enquiry inbox, sent through the
 * same mail server as staff mail. Reply-To is the customer when they gave an email. Open to the public, so: a hidden
 * bot field, and a limit per address and per day. When mail cannot go out the website falls back to FormSubmit.
 */
@Slf4j
@Service
public class EnquiryService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a", Locale.ENGLISH);
    private static final int PER_ADDRESS = 5;
    private static final Duration PER_ADDRESS_WINDOW = Duration.ofMinutes(10);
    private static final int PER_DAY = 150;

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailService mailService;
    private final String to;
    private final String from;
    private final String brand;

    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();
    private final Deque<Instant> today = new ArrayDeque<>();

    public EnquiryService(ObjectProvider<JavaMailSender> mailSender, MailService mailService,
                          @Value("${app.enquiry.to:info.amsidel@gmail.com}") String to,
                          @Value("${app.enquiry.brand:Amsidel Doors & Windows}") String brand) {
        this.mailSender = mailSender;
        this.mailService = mailService;
        this.to = to;
        this.from = mailService.setup().from();
        this.brand = brand;
    }

    /** Sends the enquiry; 503 when mail is not set up or the mail server failed, so the website uses its fallback. */
    public void send(EnquiryRequest r, String ip) {
        if (StringUtils.hasText(r.getWebsite())) {
            log.info("Website enquiry dropped: bot field filled ({})", ip);
            return;
        }
        allow(ip);
        if (!mailService.ready() || !StringUtils.hasText(to)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Mail is not set up on the server");
        }
        String subject = oneLine(subject(r));
        try {
            MimeMessage message = mailSender.getObject().createMimeMessage();
            MimeMessageHelper mail = new MimeMessageHelper(message, true, "UTF-8");
            mail.setFrom(from, brand + " website");
            mail.setTo(to);
            if (StringUtils.hasText(r.getEmail())) {
                mail.setReplyTo(r.getEmail().strip(), oneLine(r.getName()));
            }
            mail.setSubject(subject);
            mail.setText(plain(r), html(r));
            mailSender.getObject().send(message);
            log.info("Website enquiry '{}' mailed", subject);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.warn("Website enquiry '{}' could not be mailed: {}", subject, e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The enquiry could not be mailed");
        }
    }

    /** At most 5 in 10 minutes from one address and 150 a day in all (the mail plan's daily limit is about 300). */
    private synchronized void allow(String ip) {
        Instant now = Instant.now();
        while (!today.isEmpty() && today.peekFirst().isBefore(now.minus(Duration.ofDays(1)))) {
            today.pollFirst();
        }
        recent.values().removeIf(q -> q.isEmpty() || q.peekLast().isBefore(now.minus(PER_ADDRESS_WINDOW)));
        Deque<Instant> mine = recent.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        while (!mine.isEmpty() && mine.peekFirst().isBefore(now.minus(PER_ADDRESS_WINDOW))) {
            mine.pollFirst();
        }
        if (mine.size() >= PER_ADDRESS || today.size() >= PER_DAY) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many enquiries; please call us instead");
        }
        mine.addLast(now);
        today.addLast(now);
    }

    private static String subject(EnquiryRequest r) {
        String name = r.getName().strip();
        String headline = StringUtils.hasText(r.getHeadline()) ? r.getHeadline().strip() : null;
        return switch (r.getKind()) {
            case QUOTE -> "New website enquiry" + (headline == null ? "" : " – " + headline) + " (" + name + ")";
            case SITE_VISIT -> "Site visit request" + (headline == null ? "" : " – " + headline) + " (" + name + ")";
            case CHAT -> "AMI chat – query from " + name;
        };
    }

    private static String label(Kind kind) {
        return switch (kind) {
            case QUOTE -> "New enquiry";
            case SITE_VISIT -> "Site visit request";
            case CHAT -> "AMI chat";
        };
    }

    private static String heading(EnquiryRequest r) {
        if (StringUtils.hasText(r.getHeadline())) {
            return r.getHeadline().strip();
        }
        return switch (r.getKind()) {
            case QUOTE -> "Quote request";
            case SITE_VISIT -> "Free site visit";
            case CHAT -> "Query from " + r.getName().strip();
        };
    }

    private String received() {
        return WHEN.format(Instant.now().atZone(IST)) + " IST";
    }

    // ---- the mail ----

    private String plain(EnquiryRequest r) {
        StringBuilder t = new StringBuilder();
        t.append(label(r.getKind()).toUpperCase(Locale.ROOT)).append(": ").append(heading(r)).append("\n\n");
        t.append("Name: ").append(r.getName().strip()).append('\n');
        t.append("Phone: ").append(r.getPhone().strip()).append('\n');
        if (StringUtils.hasText(r.getEmail())) {
            t.append("Email: ").append(r.getEmail().strip()).append('\n');
        }
        for (EnquiryRequest.Row row : rows(r)) {
            t.append(row.getLabel().strip()).append(": ").append(value(row)).append('\n');
        }
        if (StringUtils.hasText(r.getMessage())) {
            t.append("\nMessage:\n").append(r.getMessage().strip()).append('\n');
        }
        if (r.getTranscript() != null && !r.getTranscript().isEmpty()) {
            t.append("\nChat:\n");
            for (EnquiryRequest.ChatLine line : r.getTranscript()) {
                t.append(line.isVisitor() ? r.getName().strip() : "AMI").append(": ").append(text(line)).append('\n');
            }
        }
        t.append("\nReceived ").append(received());
        if (StringUtils.hasText(r.getPage())) {
            t.append(" from ").append(r.getPage().strip());
        }
        return t.append('\n').toString();
    }

    private String html(EnquiryRequest r) {
        String name = r.getName().strip();
        String phone = r.getPhone().strip();
        String digits = phoneDigits(phone);
        String email = StringUtils.hasText(r.getEmail()) ? r.getEmail().strip() : null;

        StringBuilder buttons = new StringBuilder();
        buttons.append(button("tel:+" + digits, "Call " + phone, "#b8893b", "#ffffff"));
        if (digits.length() >= 11) {
            buttons.append(button("https://wa.me/" + digits, "WhatsApp", "#1f8f4e", "#ffffff"));
        }
        if (email != null) {
            buttons.append(button("mailto:" + email + "?subject=" + url("Re: " + subject(r)), "Reply by email", "#ffffff", "#1c1b19"));
        }

        StringBuilder details = new StringBuilder();
        details.append(row("Name", "<b>" + esc(name) + "</b>"));
        details.append(row("Phone", "<a href=\"tel:+" + digits + "\" style=\"color:#1c1b19;text-decoration:none\">" + esc(phone) + "</a>"));
        if (email != null) {
            details.append(row("Email", "<a href=\"mailto:" + esc(email) + "\" style=\"color:#8a6420\">" + esc(email) + "</a>"));
        }
        for (EnquiryRequest.Row row : rows(r)) {
            details.append(row(row.getLabel().strip(), esc(value(row)).replace("\n", "<br>")));
        }

        String message = !StringUtils.hasText(r.getMessage()) ? "" : """
                <tr><td style="padding:4px 32px 8px">
                <div style="font-size:11px;letter-spacing:2px;text-transform:uppercase;color:#8a8478;margin:0 0 8px">Message</div>
                <div style="background:#f6f3ee;border-left:3px solid #b8893b;border-radius:4px;padding:14px 16px;font-size:15px;line-height:1.6;color:#2b2925">%s</div>
                </td></tr>""".formatted(esc(r.getMessage().strip()).replace("\n", "<br>"));

        StringBuilder chat = new StringBuilder();
        if (r.getTranscript() != null && !r.getTranscript().isEmpty()) {
            chat.append("""
                    <tr><td style="padding:12px 32px 8px">
                    <div style="font-size:11px;letter-spacing:2px;text-transform:uppercase;color:#8a8478;margin:0 0 8px">Chat</div>
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse">""");
            for (EnquiryRequest.ChatLine line : r.getTranscript()) {
                boolean visitor = line.isVisitor();
                chat.append("""
                        <tr><td align="%s" style="padding:3px 0">
                        <div style="display:inline-block;max-width:85%%;text-align:left;background:%s;color:%s;border-radius:10px;padding:8px 12px;font-size:14px;line-height:1.5">
                        <div style="font-size:11px;font-weight:bold;opacity:.75;margin-bottom:2px">%s</div>%s</div>
                        </td></tr>""".formatted(visitor ? "right" : "left", visitor ? "#1c1b19" : "#f1ece3",
                        visitor ? "#ffffff" : "#2b2925", esc(visitor ? name : "AMI"), esc(text(line)).replace("\n", "<br>")));
            }
            chat.append("</table></td></tr>");
        }

        String page = !StringUtils.hasText(r.getPage()) ? ""
                : " from <a href=\"" + esc(r.getPage().strip()) + "\" style=\"color:#8a8478\">" + esc(shortPage(r.getPage())) + "</a>";

        return """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#f6f3ee;font-family:Arial,Helvetica,sans-serif;color:#2b2925">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f6f3ee;padding:24px 12px">
                <tr><td align="center">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:600px;background:#ffffff;border-radius:10px;overflow:hidden;border-collapse:collapse">
                <tr><td style="background:#1c1b19;padding:18px 32px">
                  <span style="color:#ffffff;font-size:16px;letter-spacing:5px">AMSIDEL</span>
                  <span style="color:#c9a45c;font-size:11px;letter-spacing:3px;padding-left:8px">%s</span>
                </td></tr>
                <tr><td style="height:3px;background:#b8893b;font-size:0;line-height:0">&nbsp;</td></tr>
                <tr><td style="padding:28px 32px 8px">
                  <div style="display:inline-block;background:#f3ead9;color:#8a6420;font-size:11px;font-weight:bold;letter-spacing:2px;text-transform:uppercase;border-radius:20px;padding:5px 12px">%s</div>
                  <h1 style="margin:14px 0 4px;font-size:22px;line-height:1.3;font-weight:normal;color:#1c1b19">%s</h1>
                  <div style="font-size:13px;color:#8a8478">Received %s</div>
                </td></tr>
                <tr><td style="padding:16px 32px 4px">%s</td></tr>
                <tr><td style="padding:16px 32px 12px">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;border-top:1px solid #ece7de">%s</table>
                </td></tr>
                %s
                %s
                <tr><td style="padding:20px 32px 24px;font-size:12px;line-height:1.6;color:#8a8478;border-top:1px solid #ece7de">
                  Sent by the %s website%s.%s
                </td></tr>
                </table>
                </td></tr></table></body></html>
                """.formatted(esc(brand.replaceFirst("^Amsidel\\s*", "").toUpperCase(Locale.ROOT)), esc(label(r.getKind())),
                esc(heading(r)), received(), buttons, details, message, chat, esc(brand), page,
                email == null ? "" : " Reply to this mail to answer " + esc(name) + " directly.");
    }

    /** Digits with the country code; ten digits are taken as an Indian mobile (+91). */
    private static String phoneDigits(String phone) {
        String digits = phone.replaceAll("\\D", "").replaceFirst("^0+", "");
        return digits.length() == 10 ? "91" + digits : digits;
    }

    private static List<EnquiryRequest.Row> rows(EnquiryRequest r) {
        return r.getDetails() == null ? List.of() : r.getDetails();
    }

    private static String value(EnquiryRequest.Row row) {
        return StringUtils.hasText(row.getValue()) ? row.getValue().strip() : "–";
    }

    private static String text(EnquiryRequest.ChatLine line) {
        return line.getText() == null ? "" : line.getText().strip();
    }

    private static String row(String label, String valueHtml) {
        return """
                <tr><td style="padding:10px 12px 10px 0;border-bottom:1px solid #ece7de;width:36%%;vertical-align:top;font-size:12px;letter-spacing:1px;text-transform:uppercase;color:#8a8478">%s</td>
                <td style="padding:10px 0;border-bottom:1px solid #ece7de;vertical-align:top;font-size:15px;line-height:1.5;color:#2b2925">%s</td></tr>"""
                .formatted(esc(label), valueHtml);
    }

    private static String button(String href, String text, String background, String colour) {
        String border = "#ffffff".equals(background) ? "#1c1b19" : background;
        return """
                <a href="%s" style="display:inline-block;margin:0 8px 8px 0;padding:10px 18px;border-radius:24px;border:1px solid %s;background:%s;color:%s;font-size:14px;font-weight:bold;text-decoration:none">%s</a>"""
                .formatted(esc(href), border, background, colour, esc(text));
    }

    private static String shortPage(String page) {
        String p = page.strip().replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        return p.length() > 60 ? p.substring(0, 60) + "…" : p;
    }

    private static String url(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s.replace("\r\n", "\n"));
    }

    private static String oneLine(String s) {
        return s.replaceAll("[\\r\\n]+", " ").strip();
    }
}
