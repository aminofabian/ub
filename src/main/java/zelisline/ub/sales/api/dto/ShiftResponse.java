package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ShiftResponse(
        String id,
        String branchId,
        String branchName,
        String status,
        BigDecimal openingCash,
        BigDecimal expectedClosingCash,
        BigDecimal countedClosingCash,
        BigDecimal closingVariance,
        String openingNotes,
        String closingNotes,
        String varianceReason,
        String openedBy,
        String openedByName,
        String closedBy,
        String closedByName,
        Instant openedAt,
        Instant closedAt,
        String closeJournalEntryId,
        List<DenominationResponse> openingDenominations,
        List<DenominationResponse> closingDenominations
) {
}
