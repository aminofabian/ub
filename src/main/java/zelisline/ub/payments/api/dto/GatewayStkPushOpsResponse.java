package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Super-admin / ops view of one gateway STK push (all rails).
 */
public record GatewayStkPushOpsResponse(
        String id,
        String businessId,
        String businessName,
        String businessSlug,
        String gatewayType,
        String gatewayCheckoutId,
        String merchantReference,
        String contextType,
        String contextId,
        BigDecimal amount,
        String phoneNumber,
        String status,
        String gatewayTransactionId,
        String failureReason,
        Instant confirmedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
