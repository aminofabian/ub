package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record KioskPayLedgerEntryResponse(
        String id,
        String entryType,
        String direction,
        BigDecimal amount,
        String currency,
        BigDecimal availableDelta,
        BigDecimal pendingDelta,
        BigDecimal balanceAfterAvailable,
        String reference,
        String contextType,
        String contextId,
        String note,
        Instant createdAt
) {
}
