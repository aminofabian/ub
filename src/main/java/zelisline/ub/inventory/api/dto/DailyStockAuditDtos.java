package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class DailyStockAuditDtos {

    private DailyStockAuditDtos() {
    }

    public record DailyStockAuditItemSummary(
            String itemId,
            String itemName,
            String itemSku,
            String barcode,
            String categoryName,
            String unitType,
            String imageUrl,
            int sortOrder
    ) {}

    public record DailyStockAuditSessionSummary(
            String sessionId,
            String sessionType,
            String status,
            int currentLineIndex,
            int submittedCount,
            int totalCount
    ) {}

    public record DailyStockAuditTodayResponse(
            String auditId,
            LocalDate auditDate,
            String branchId,
            int itemCount,
            Instant generatedAt,
            List<DailyStockAuditItemSummary> items,
            DailyStockAuditSessionSummary morningSession,
            DailyStockAuditSessionSummary eveningSession,
            /** Local wall-clock times ({@code HH:mm}) and countdown anchors for counters. */
            String morningStartsAt,
            String eveningStartsAt,
            String countingEndsAt,
            String timezone,
            /** {@code "morning"}, {@code "evening"}, or {@code null} when counting is closed. */
            String activeSessionType,
            /** When the current open phase ends; null when no phase is open. */
            Instant phaseEndsAt,
            /** When the next count window opens; null when counting has ended for today. */
            Instant nextOpensAt
    ) {}

    public record DailyStockAuditSessionResponse(
            String sessionId,
            String auditId,
            LocalDate auditDate,
            String branchId,
            String sessionType,
            String status,
            int currentLineIndex,
            int totalCount,
            int submittedCount,
            List<DailyStockAuditLineResponse> lines
    ) {}

    public record DailyStockAuditLineResponse(
            String lineId,
            String itemId,
            String itemName,
            String itemSku,
            String barcode,
            String categoryName,
            String unitType,
            String imageUrl,
            BigDecimal countedQty,
            String note,
            String status,
            Instant submittedAt,
            int sortOrder,
            BigDecimal systemStock
    ) {}

    public record DailyStockAuditReviewResponse(
            String auditId,
            LocalDate auditDate,
            String branchId,
            int itemCount,
            List<DailyStockAuditReviewLineResponse> lines
    ) {}

    public record DailyStockAuditReviewLineResponse(
            String itemId,
            String itemName,
            String itemSku,
            String barcode,
            String categoryName,
            String unitType,
            String imageUrl,
            BigDecimal morningCount,
            BigDecimal eveningCount,
            BigDecimal systemStock,
            BigDecimal expectedStock,
            BigDecimal variance,
            boolean matches,
            String reviewStatus,
            String reviewNotes,
            String reviewedBy,
            Instant reviewedAt,
            int sortOrder
    ) {}

    public record DailyStockAuditInvestigationResponse(
            String auditId,
            LocalDate auditDate,
            String branchId,
            String itemId,
            String itemName,
            String itemSku,
            BigDecimal morningCount,
            BigDecimal eveningCount,
            BigDecimal systemStock,
            BigDecimal expectedStock,
            BigDecimal variance,
            String reviewNotes,
            String reviewedBy,
            Instant reviewedAt
    ) {}
}
