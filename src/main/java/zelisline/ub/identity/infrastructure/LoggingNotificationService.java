package zelisline.ub.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.NotificationService;

/**
 * Delivers email when {@link JavaMailSender} is configured; otherwise logs (local dev).
 */
@Service
@RequiredArgsConstructor
public class LoggingNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationService.class);

    private final ObjectProvider<JavaMailSender> javaMailSender;

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
        JavaMailSender sender = javaMailSender.getIfAvailable();
        if (sender != null) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(toEmail);
                message.setSubject(subject);
                message.setText(textBody);
                sender.send(message);
                return;
            } catch (RuntimeException ex) {
                log.warn("Failed to send {} email to={}", kind, toEmail, ex);
            }
        }
        log.info("[notification] {} to={} subject={}\n{}", kind, toEmail, subject, textBody);
    }
}
