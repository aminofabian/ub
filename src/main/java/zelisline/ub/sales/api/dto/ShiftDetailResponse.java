package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Full shift detail including denomination breakdowns.
 */
public record ShiftDetailResponse(
        String id,
        String branchId,
        String branchName,
        String status,
        BigDecimal openingFloat,
        BigDecimal expectedCash,
        BigDecimal actualCountedCash,
        BigDecimal variance,
        String openingNotes,
        String closingNotes,
        String varianceReason,
        String openedBy,
        String openedByName,
        String closedBy,
        String closedByName,
        String reconciledBy,
        Instant openedAt,
        Instant closedAt,
        Instant reconciledAt,
        boolean blindClosing,
        List<DenominationResponse> openingDenominations,
        List<DenominationResponse> closingDenominations,
        List<ShiftListItemResponse.SalesSummary> salesSummary,
        List<ShiftExpenseResponse> expenses,
        List<ShiftAuditEntryResponse> auditLog
) {

    /**
     * Simplified summary for the list view in column 1.
     */
    public ShiftListItemResponse toListItem() {
        return new ShiftListItemResponse(
                id, branchId, branchName, status,
                openedByName, openedBy,
                openedAt, closedAt,
                openingFloat, actualCountedCash, expectedCash, variance,
                salesSummary != null && !salesSummary.isEmpty()
                        ? salesSummary.getFirst().transactionCount()
                        : 0,
                BigDecimal.ZERO, // total sales will be computed from salesSummary
                null, null
        );
    }
}
