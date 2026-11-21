package zelisline.ub.identity.infrastructure;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.MailAttachment;
import zelisline.ub.identity.application.NotificationService;

/**
 * Sends via {@link JavaMailSender} when configured, else Resend HTTP API, else Mailgun HTTP API,
 * else logs (local dev only).
 */
@Service
@RequiredArgsConstructor
public class LoggingNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationService.class);

    private final ObjectProvider<JavaMailSender> javaMailSender;
    private final ResendMailClient resendMailClient;
    private final MailgunMailClient mailgunMailClient;

    /** Boot-time report so misconfig is obvious in dev and prod logs. */
    @PostConstruct
    public void reportProvider() {
        boolean smtp = javaMailSender.getIfAvailable() != null;
        boolean resend = resendMailClient.isConfigured();
        boolean mailgun = mailgunMailClient.isConfigured();
        if (smtp) {
            log.info("[mail] active provider: SMTP (JavaMailSender). Resend configured={} Mailgun configured={}",
                    resend, mailgun);
        } else if (resend) {
            log.info("[mail] active provider: Resend. Mailgun configured={}", mailgun);
        } else if (mailgun) {
            log.info("[mail] active provider: Mailgun");
        } else {
            log.warn(
                    "[mail] NO MAIL PROVIDER ACTIVE — emails will only be logged. "
                            + "Set RESEND_API_KEY (+ RESEND_DOMAIN or RESEND_FROM), or MAILGUN_PRIVATE_API_KEY (+ MAILGUN_DOMAIN), "
                            + "or activate the `smtp` profile with MAILGUN_SMTP_*. RESTART the JVM after changing env vars. "
                            + "CONSEQUENCE: signup verification links are returned in the register response instead of "
                            + "being emailed (see AuthRegistrationService.shouldExposeVerificationLink), so the UI shows "
                            + "the link on screen. Fix the credentials above to send real mail.");
        }
    }

    @Override
    public boolean canDeliverEmail() {
        return javaMailSender.getIfAvailable() != null
                || resendMailClient.isConfigured()
                || mailgunMailClient.isConfigured();
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String subject, String textBody) {
        sendOrLog(toEmail, subject, textBody, "password reset");
    }

    @Override
    public void sendTemporaryLockNotice(String toEmail) {
        String body = "Your account was temporarily locked after repeated failed sign-in attempts.";
        sendOrLog(toEmail, "UB account temporarily locked", body, "lock notice");
    }

    @Override
    public void sendEmailVerificationEmail(String toEmail, String subject, String htmlBody) {
        sendOrLogHtml(toEmail, subject, htmlBody, "email verification");
    }

    @Override
    public void sendWelcomeEmail(String toEmail, String subject, String htmlBody) {
        sendOrLogHtml(toEmail, subject, htmlBody, "welcome", "Kiosk");
    }

    @Override
    public void sendOrderConfirmationHtml(
            String toEmail, String subject, String htmlBody, String fromDisplayName) {
        sendOrLogHtml(toEmail, subject, htmlBody, "order confirmation", fromDisplayName);
    }

    @Override
    public void sendNotificationEmail(String toEmail, String subject, String textBody, String htmlBody) {
        sendNotificationEmail(toEmail, subject, textBody, htmlBody, null);
    }

    @Override
    public void sendNotificationEmail(
            String toEmail, String subject, String textBody, String htmlBody, String fromDisplayName) {
        if (htmlBody != null && !htmlBody.isBlank()) {
            sendOrLogHtml(toEmail, subject, htmlBody, "notification", fromDisplayName);
            return;
        }
        sendOrLog(toEmail, subject, textBody != null ? textBody : "", "notification");
    }

    @Override
    public void sendNotificationEmail(
            String toEmail,
            String subject,
            String textBody,
            String htmlBody,
            String fromDisplayName,
            List<MailAttachment> attachments
    ) {
        if (attachments == null || attachments.isEmpty()) {
            sendNotificationEmail(toEmail, subject, textBody, htmlBody, fromDisplayName);
            return;
        }
        sendWithAttachments(toEmail, subject, textBody, htmlBody, fromDisplayName, attachments);
    }

    @Override
    public void sendPlatformCampaignEmail(
            String toEmail, String subject, String textBody, String htmlBody, String fromDisplayName) {
        if (htmlBody != null && !htmlBody.isBlank()) {
            sendOrLogHtml(toEmail, subject, htmlBody, "platform campaign", fromDisplayName);
            return;
        }
        sendOrLog(toEmail, subject, textBody != null ? textBody : "", "platform campaign");
    }

    @Override
    public void sendContactReplyEmail(
            String toEmail, String subject, String textBody, String fromDisplayName) {
        String safe = textBody == null ? "" : textBody;
        String escaped = safe
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br/>");
        String html = "<!DOCTYPE html><html><body style=\"font-family:sans-serif;line-height:1.5\">"
                + escaped
                + "</body></html>";
        sendOrLogHtml(toEmail, subject, html, "contact reply", fromDisplayName);
    }

    private void sendWithAttachments(
            String toEmail,
            String subject,
            String textBody,
            String htmlBody,
            String fromDisplayName,
            List<MailAttachment> attachments
    ) {
        if (trySendSmtpWithAttachments(toEmail, subject, textBody, htmlBody, attachments)) {
            return;
        }
        if (trySendResendWithAttachments(toEmail, subject, htmlBody, fromDisplayName, attachments)) {
            return;
        }
        if (trySendMailgunWithAttachments(toEmail, subject, textBody, htmlBody, fromDisplayName, attachments)) {
            return;
        }
        log.info("[notification-html] with attachments to={} subject={} files={}",
                toEmail, subject, attachmentNames(attachments));
    }

    private boolean trySendSmtpWithAttachments(
            String toEmail, String subject, String textBody, String htmlBody, List<MailAttachment> attachments) {
        JavaMailSender sender = javaMailSender.getIfAvailable();
        if (sender == null) {
            return false;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            if (htmlBody != null && !htmlBody.isBlank()) {
                helper.setText(textBody == null ? "" : textBody, htmlBody);
            } else {
                helper.setText(textBody == null ? "" : textBody, false);
            }
            addSmtpAttachments(helper, attachments);
            sender.send(message);
            log.info("Sent notification with attachments via SMTP to={} files={}",
                    toEmail, attachmentNames(attachments));
            return true;
        } catch (MessagingException | RuntimeException ex) {
            log.warn("Failed to send notification with attachments via SMTP to={}", toEmail, ex);
            return false;
        }
    }

    private static void addSmtpAttachments(MimeMessageHelper helper, List<MailAttachment> attachments)
            throws MessagingException {
        for (MailAttachment file : attachments) {
            if (file.content().length == 0) {
                continue;
            }
            helper.addAttachment(file.filename(), new ByteArrayResource(file.content()), file.mediaType());
        }
    }

    private boolean trySendResendWithAttachments(
            String toEmail,
            String subject,
            String htmlBody,
            String fromDisplayName,
            List<MailAttachment> attachments
    ) {
        if (!resendMailClient.isConfigured() || htmlBody == null || htmlBody.isBlank()) {
            return false;
        }
        try {
            resendMailClient.sendHtml(toEmail, subject, htmlBody, fromDisplayName, attachments);
            log.info("Sent notification with attachments via Resend to={} files={}",
                    toEmail, attachmentNames(attachments));
            return true;
        } catch (RuntimeException ex) {
            log.warn("Resend attachments failed to={}", toEmail, ex);
            return false;
        }
    }

    private boolean trySendMailgunWithAttachments(
            String toEmail,
            String subject,
            String textBody,
            String htmlBody,
            String fromDisplayName,
            List<MailAttachment> attachments
    ) {
        if (!mailgunMailClient.isConfigured() || htmlBody == null || htmlBody.isBlank()) {
            return false;
        }
        try {
            mailgunMailClient.sendHtml(toEmail, subject, htmlBody, textBody, fromDisplayName, attachments);
            log.info("Sent notification with attachments via Mailgun to={} files={}",
                    toEmail, attachmentNames(attachments));
            return true;
        } catch (RuntimeException ex) {
            log.warn("Mailgun attachments failed to={}", toEmail, ex);
            return false;
        }
    }

    private static String attachmentNames(List<MailAttachment> attachments) {
        StringBuilder names = new StringBuilder();
        for (MailAttachment file : attachments) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(file.filename());
        }
        return names.toString();
    }

    private void sendOrLogHtml(String toEmail, String subject, String htmlBody, String kind) {
        sendOrLogHtml(toEmail, subject, htmlBody, kind, null);
    }

    private void sendOrLogHtml(
            String toEmail, String subject, String htmlBody, String kind, String fromDisplayName) {
        if (trySendSmtpHtml(toEmail, subject, htmlBody, kind)) {
            return;
        }
        if (trySendResendHtml(toEmail, subject, htmlBody, kind, fromDisplayName)) {
            return;
        }
        if (trySendMailgunHtml(toEmail, subject, htmlBody, kind, fromDisplayName)) {
            return;
        }
        log.warn(
                "No outbound mail: {} not delivered. Set RESEND_API_KEY + RESEND_DOMAIN or RESEND_FROM (Resend), "
                        + "or MAILGUN_PRIVATE_API_KEY + MAILGUN_DOMAIN (Mailgun), or profile `{}` + MAILGUN_SMTP_*; "
                        + "else copy the link from the INFO log below.",
                kind,
                "smtp");
        String truncated = htmlBody != null && htmlBody.length() > 500
                ? htmlBody.substring(0, 500) + "..."
                : htmlBody;
        log.info("[notification-html] {} to={} subject={} fromDisplayName={}\n{}",
                kind, toEmail, subject, fromDisplayName, truncated);
    }

    private boolean trySendSmtpHtml(String toEmail, String subject, String htmlBody, String kind) {
        JavaMailSender sender = javaMailSender.getIfAvailable();
        if (sender == null) {
            return false;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            sender.send(message);
            return true;
        } catch (MessagingException | RuntimeException ex) {
            log.warn("Failed to send {} HTML email via SMTP to={}", kind, toEmail, ex);
            return false;
        }
    }

    private boolean trySendResendHtml(
            String toEmail, String subject, String htmlBody, String kind, String fromDisplayName) {
        if (!resendMailClient.isConfigured()) {
            return false;
        }
        try {
            resendMailClient.sendHtml(toEmail, subject, htmlBody, fromDisplayName);
            log.info("Sent {} HTML via Resend to={} fromDisplayName={}", kind, toEmail, fromDisplayName);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Resend HTML failed for {} to={}", kind, toEmail, ex);
            return false;
        }
    }

    private boolean trySendMailgunHtml(
            String toEmail, String subject, String htmlBody, String kind, String fromDisplayName) {
        if (!mailgunMailClient.isConfigured()) {
            return false;
        }
        try {
            mailgunMailClient.sendHtml(toEmail, subject, htmlBody, fromDisplayName);
            log.info("Sent {} HTML via Mailgun to={} fromDisplayName={}", kind, toEmail, fromDisplayName);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Mailgun HTML failed for {} to={}", kind, toEmail, ex);
            return false;
        }
    }

    private void sendOrLog(String toEmail, String subject, String textBody, String kind) {
        if (trySendSmtp(toEmail, subject, textBody, kind)) {
            return;
        }
        if (trySendResend(toEmail, subject, textBody, kind)) {
            return;
        }
        if (trySendMailgun(toEmail, subject, textBody, kind)) {
            return;
        }
        log.warn(
                "No outbound mail: {} not delivered. Set RESEND_API_KEY + RESEND_DOMAIN or RESEND_FROM (Resend), "
                        + "or MAILGUN_PRIVATE_API_KEY + MAILGUN_DOMAIN (Mailgun), or profile `{}` + MAILGUN_SMTP_*; "
                        + "else copy the link from the INFO log below.",
                kind,
                "smtp");
        log.info("[notification] {} to={} subject={}\n{}", kind, toEmail, subject, textBody);
    }

    private boolean trySendSmtp(String toEmail, String subject, String textBody, String kind) {
        JavaMailSender sender = javaMailSender.getIfAvailable();
        if (sender == null) {
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(textBody);
            sender.send(message);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Failed to send {} email via SMTP to={}", kind, toEmail, ex);
            return false;
        }
    }

    private boolean trySendResend(String toEmail, String subject, String textBody, String kind) {
        if (!resendMailClient.isConfigured()) {
            return false;
        }
        try {
            resendMailClient.sendPlainText(toEmail, subject, textBody);
            log.info("Sent {} via Resend to={}", kind, toEmail);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Resend failed for {} to={}", kind, toEmail, ex);
            return false;
        }
    }

    private boolean trySendMailgun(String toEmail, String subject, String textBody, String kind) {
        if (!mailgunMailClient.isConfigured()) {
            return false;
        }
        try {
            mailgunMailClient.sendPlainText(toEmail, subject, textBody);
            log.info("Sent {} via Mailgun to={}", kind, toEmail);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Mailgun failed for {} to={}", kind, toEmail, ex);
            return false;
        }
    }
}
