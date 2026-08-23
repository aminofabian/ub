package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class RestockDigestDtos {

    private RestockDigestDtos() {
    }

    public record RestockSuggestionResponse(
            String id,
            String runId,
            String itemId,
            String itemName,
            String itemSku,
            String supplierId,
            String supplierName,
            String target,
            BigDecimal onHand,
            BigDecimal inbound,
            BigDecimal reorderLevel,
            BigDecimal par,
            BigDecimal suggestedQty,
            BigDecimal acceptedQty,
            BigDecimal unitCost,
            BigDecimal packSize,
            Integer leadTimeDays,
            String reasonCode,
            String evidence,
            String confidence,
            String status,
            LocalDate snoozeUntil,
            String purchaseOrderId,
            String orderPadItemId,
            Instant createdAt
    ) {}

    public record RestockRunResponse(
            String id,
            String businessId,
            String branchId,
            String branchName,
            LocalDate runDate,
            Instant generatedAt,
            String status,
            int lineCount,
            int poLineCount,
            int padLineCount,
            BigDecimal estTotal,
            String currency,
            String trigger,
            String errorNote,
            List<RestockSuggestionResponse> suggestions
    ) {}

    public record RestockRunListRow(
            String id,
            String branchId,
            String branchName,
            LocalDate runDate,
            Instant generatedAt,
            String status,
            int lineCount,
            BigDecimal estTotal,
            String currency,
            String trigger
    ) {}

    /** Accept a run (or part of it) into draft POs + order pad lines. */
    public record AcceptRestockRunRequest(
            /** Null / empty = all pending lines of the requested {@code mode}. */
            List<String> lineIds,
            /** suggestionId → accepted qty override (optional). */
            java.util.Map<String, BigDecimal> qtyOverrides,
            /** po | pad | all (default all). */
            String mode
    ) {}

    public record CreatedPurchaseOrderRef(
            String purchaseOrderId,
            String poNumber,
            String supplierId,
            String supplierName,
            int lineCount
    ) {}

    /** A pending line the accept skipped (missing unit cost / missing permission). */
    public record SkippedAcceptLine(
            String suggestionId,
            String itemId,
            String itemName,
            String reason
    ) {}

    public record AcceptRestockRunResponse(
            RestockRunResponse run,
            List<CreatedPurchaseOrderRef> purchaseOrders,
            int padLinesCreated,
            List<SkippedAcceptLine> skippedLines
    ) {}

    public record SnoozeRestockSuggestionRequest(Integer days) {}

    /** Lightweight "is there an actionable run right now" summary for the grocery chip. */
    public record RestockActiveRunSummary(
            String runId,
            LocalDate runDate,
            String status,
            int lineCount
    ) {}
}
