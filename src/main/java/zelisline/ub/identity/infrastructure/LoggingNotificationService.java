package zelisline.ub.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
                            + "or activate the `smtp` profile with MAILGUN_SMTP_*. RESTART the JVM after changing env vars.");
        }
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
    public void sendEmailVerificationEmail(String toEmail, String subject, String textBody) {
        sendOrLog(toEmail, subject, textBody, "email verification");
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
