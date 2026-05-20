package zelisline.ub.payments.domain.spi;

import java.math.BigDecimal;

/**
 * Normalized result from processing a gateway webhook.
 */
public record WebhookResult(
        /** The business ID resolved from the webhook payload. */
        String businessId,

        /** Gateway-native transaction ID (for idempotency). */
        String gatewayTransactionId,

        /** The phone number that made the payment, if available. */
        String phoneNumber,

        /** Amount received in the payment. */
        BigDecimal amount,

        /** Reference / account reference from the payer. */
        String reference,

        /** Whether the payment was successful. */
        boolean success,

        /** Raw payload for audit/debug. */
        String rawPayload
) {
}
