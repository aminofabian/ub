package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Super-admin / ops view of one platform custody settlement (Model B auto-settle).
 */
public record PlatformCustodySettlementResponse(
        String id,
        String businessId,
        String gatewayConfigId,
        String stkPushId,
        String provider,
        BigDecimal amount,
        String currency,
        String destinationType,
        String destinationTill,
        String destinationPaybill,
        String destinationAccount,
        String status,
        String disbursementId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant settledAt
) {
}
