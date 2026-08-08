package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record KioskPayWithdrawalResponse(
        String businessId,
        String id,
        BigDecimal amount,
        String currency,
        String phoneNumber,
        String status,
        String failureReason,
        Instant requestedAt,
        Instant completedAt
) {
}
