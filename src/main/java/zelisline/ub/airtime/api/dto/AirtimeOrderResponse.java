package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AirtimeOrderResponse(
        String id,
        String businessId,
        String channel,
        String tender,
        String phoneNumber,
        String payerPhone,
        String network,
        BigDecimal amount,
        BigDecimal cost,
        BigDecimal commission,
        String currency,
        String status,
        String reference,
        String providerTransactionId,
        String providerStatus,
        String receipt,
        String failureReason,
        BigDecimal walletBalanceAfter,
        Instant requestedAt,
        Instant completedAt
) {
}
