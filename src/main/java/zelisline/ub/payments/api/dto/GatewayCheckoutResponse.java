package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import zelisline.ub.payments.domain.GatewayCheckoutContextType;

/**
 * Admin-facing view of a provider-hosted checkout attempt
 * (no credentials, no raw payloads).
 */
public record GatewayCheckoutResponse(
        String id,
        String gatewayType,
        String reference,
        GatewayCheckoutContextType contextType,
        String contextId,
        BigDecimal amount,
        String currency,
        String customerEmail,
        String status,
        String providerTransactionId,
        String failureReason,
        Instant createdAt,
        Instant confirmedAt
) {
}
