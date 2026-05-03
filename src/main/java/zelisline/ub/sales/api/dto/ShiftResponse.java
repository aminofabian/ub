package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ShiftResponse(
        String id,
        String branchId,
        String status,
        BigDecimal openingCash,
        BigDecimal expectedClosingCash,
        BigDecimal countedClosingCash,
        BigDecimal closingVariance,
        String openingNotes,
        String closingNotes,
        String openedBy,
        String closedBy,
        Instant openedAt,
        Instant closedAt,
        String closeJournalEntryId
) {
}
