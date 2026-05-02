package zelisline.ub.identity.application;

/**
 * Outbound user notifications (email). Default implementation logs only; replace
 * with a mail-backed bean when {@code spring.mail.host} is configured.
 */
public interface NotificationService {

    void sendPasswordResetEmail(String toEmail, String subject, String textBody);

    void sendTemporaryLockNotice(String toEmail);
}
