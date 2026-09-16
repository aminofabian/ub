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

    /** Sent immediately after self-service signup. From display name is Kiosk. */
    void sendWelcomeEmail(String toEmail, String subject, String htmlBody);

    /**
     * Whether this deployment can actually deliver outbound email.
     *
     * <p>Callers that promise a user an email (signup verification) must check
     * this: with no provider configured every send silently no-ops, so the UI
     * would claim a mail was sent that can never arrive. Defaults to true so
     * existing and test implementations keep their behaviour.
     */
    default boolean canDeliverEmail() {
        return true;
    }

    /**
     * @param fromDisplayName tenant store name for the From header (e.g. {@code Palmart});
     *                        may be null to keep the provider default
     */
    void sendOrderConfirmationHtml(String toEmail, String subject, String htmlBody, String fromDisplayName);

    /** In-app notification projection to email (plain + minimal HTML). */
    void sendNotificationEmail(String toEmail, String subject, String textBody, String htmlBody);

    default void sendNotificationEmail(
            String toEmail,
            String subject,
            String textBody,
            String htmlBody,
            String fromDisplayName
    ) {
        sendNotificationEmail(toEmail, subject, textBody, htmlBody);
    }

    default void sendPlatformCampaignEmail(
            String toEmail,
            String subject,
            String textBody,
            String htmlBody,
            String fromDisplayName
    ) {
        sendNotificationEmail(toEmail, subject, textBody, htmlBody, fromDisplayName);
    }

    /**
     * Owner/platform reply to a Talk to Us contact message.
     *
     * @param fromDisplayName shop or platform name for the From header; may be null
     */
    void sendContactReplyEmail(String toEmail, String subject, String textBody, String fromDisplayName);
}
