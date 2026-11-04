package com.medizon.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * Sends transactional emails. Fire-and-forget (@Async). When mail is disabled (MAIL_ENABLED=false)
 * the email is logged — handy in development. Supports both plain-text and rich HTML emails rendered
 * with Thymeleaf templates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final TemplateEngine templateEngine;

    @Value("${medizon.mail.enabled:false}")
    private boolean enabled;

    @Value("${medizon.mail.from:no-reply@medizon.in}")
    private String from;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    private static final byte[] LOGO_BYTES = loadLogoBytes();

    private static byte[] loadLogoBytes() {
        try (var is = EmailService.class.getResourceAsStream("/static/images/logo.png")) {
            if (is != null) return is.readAllBytes();
        } catch (Exception ignored) {}
        try (var is = EmailService.class.getResourceAsStream("/static/logo-mark.png")) {
            if (is != null) return is.readAllBytes();
        } catch (Exception ignored) {}
        return new byte[0];
    }

    /**
     * Resolves the "From" address. When configured with a Google account (or any authenticated SMTP user),
     * ensuring the From header matches the authenticated mailbox prevents Gmail / SMTP from rejecting or rewriting.
     */
    private String resolveFrom() {
        if (from != null && !from.isBlank() && !from.contains("no-reply@medizon.in")) {
            return from;
        }
        if (mailUsername != null && !mailUsername.isBlank()) {
            return "MediZon <" + mailUsername + ">";
        }
        return from != null && !from.isBlank() ? from : "MediZon <no-reply@medizon.in>";
    }

    /** Plain-text fire-and-forget. With mail disabled the email is only logged. */
    @Async
    public void send(String to, String subject, String body) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (!enabled || sender == null) {
            log.info("[mail disabled] to={} subject='{}' body=\n{}", to, subject, body);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(resolveFrom());
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
            log.info("Email sent to={} subject='{}'", to, subject);
        } catch (Exception e) {
            log.warn("Could not send email to {}: {}", to, e.getMessage());
        }
    }

    /**
     * HTML email with a plain-text fallback. Both are included in the multipart message so every
     * mail client can render something. Fire-and-forget (@Async).
     */
    @Async
    public void sendHtml(String to, String subject, String htmlBody, String textBody) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (!enabled || sender == null) {
            log.info("[mail disabled] to={} subject='{}' (html)", to, subject);
            return;
        }
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(resolveFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            if (textBody != null && !textBody.isBlank()) {
                helper.setText(textBody, htmlBody);
            } else {
                helper.setText(htmlBody, true);
            }
            if (htmlBody != null && htmlBody.contains("cid:medizonLogo") && LOGO_BYTES.length > 0) {
                helper.addInline("medizonLogo", new org.springframework.core.io.ByteArrayResource(LOGO_BYTES), "image/png");
            }
            sender.send(msg);
            log.info("HTML email sent to={} subject='{}'", to, subject);
        } catch (MessagingException e) {
            log.warn("Could not send HTML email to {}: {}", to, e.getMessage());
        }
    }

    /**
     * HTML email with a file attached (an invoice PDF). Render the body and the file on the calling thread; this
     * only sends them. Fire-and-forget (@Async).
     */
    @Async
    public void sendHtmlWithAttachment(String to, String subject, String htmlBody, String textBody,
                                       String fileName, byte[] file, String contentType) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (!enabled || sender == null) {
            log.info("[mail disabled] to={} subject='{}' attachment={} ({} bytes)", to, subject, fileName, file.length);
            return;
        }
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(resolveFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(textBody == null || textBody.isBlank() ? subject : textBody, htmlBody);
            if (htmlBody != null && htmlBody.contains("cid:medizonLogo") && LOGO_BYTES.length > 0) {
                helper.addInline("medizonLogo", new org.springframework.core.io.ByteArrayResource(LOGO_BYTES), "image/png");
            }
            helper.addAttachment(fileName, new org.springframework.core.io.ByteArrayResource(file), contentType);
            sender.send(msg);
            log.info("Email with attachment sent to={} subject='{}' file={}", to, subject, fileName);
        } catch (Exception e) {
            log.warn("Could not send email with attachment to {}: {}", to, e.getMessage());
        }
    }

    /** Renders a Thymeleaf HTML email template on the calling thread. */
    public String render(String templateName, Map<String, Object> variables) {
        Context context = new Context();
        if (variables != null) context.setVariables(variables);
        return templateEngine.process(templateName, context);
    }

    /**
     * Renders a Thymeleaf HTML email template and sends it asynchronously. Pass plain values only, never JPA
     * entities: they are read on the mail thread, after the caller's session is gone.
     */
    @Async
    public void sendTemplate(String to, String subject, String templateName, Map<String, Object> variables, String fallbackText) {
        try {
            Context context = new Context();
            if (variables != null) {
                context.setVariables(variables);
            }
            String htmlContent = templateEngine.process(templateName, context);
            sendHtml(to, subject, htmlContent, fallbackText != null ? fallbackText : subject);
        } catch (Exception e) {
            log.warn("Could not render/send template '{}' to {}: {}", templateName, to, e.getMessage());
            // Fallback to plain text if HTML rendering failed
            if (fallbackText != null && !fallbackText.isBlank()) {
                send(to, subject, fallbackText);
            }
        }
    }
}
