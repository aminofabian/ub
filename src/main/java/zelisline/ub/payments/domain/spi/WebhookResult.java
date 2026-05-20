package zelisline.ub.payments.domain.spi;

import java.math.BigDecimal;

/**
 * Normalized result from processing a gateway webhook.
 */
public record WebhookResult(
        String businessId,
        String gatewayTransactionId,
        String phoneNumber,
        BigDecimal amount,
        /** Merchant reference from STK metadata or bill ref. */
        String reference,
        boolean success,
        /** True when gateway reports a terminal failure (not merely unknown). */
        boolean terminalFailure,
        /** Incoming-payment / STK checkout id when present in payload. */
        String gatewayCheckoutId,
        /** Gateway webhook event id for idempotency. */
        String webhookEventId,
        String topic,
        String rawPayload
) {
    public static WebhookResult empty(String rawPayload) {
        return new WebhookResult(null, null, null, null, null, false, false, null, null, null, rawPayload);
    }
}
