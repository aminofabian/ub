package zelisline.ub.identity.application;

/**
 * Outbound user notifications (email). Default implementation logs only; replace
 * with a mail-backed bean when {@code spring.mail.host} is configured.
 */
public interface NotificationService {

    void sendPasswordResetEmail(String toEmail, String subject, String textBody);

    void sendTemporaryLockNotice(String toEmail);

    /** {@code htmlBody} is a complete HTML document (inline CSS). */
    void sendEmailVerificationEmail(String toEmail, String subject, String htmlBody);

    void sendOrderConfirmationHtml(String toEmail, String subject, String htmlBody);

    /** In-app notification projection to email (plain + minimal HTML). */
    void sendNotificationEmail(String toEmail, String subject, String textBody, String htmlBody);
}
