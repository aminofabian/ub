package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class StockCountCycleDtos {

    private StockCountCycleDtos() {
    }

    /** Landing state for a branch: the latest cycle (if any) and catalog size for planning. */
    public record CountCycleHomeResponse(
            CountCycleResponse cycle,
            int catalogSize,
            int defaultBatchSize
    ) {}

    public record CountCycleResponse(
            String cycleId,
            String branchId,
            String status,
            int batchSize,
            int totalItems,
            int countedItems,
            int remainingItems,
            int batchesDone,
            int daysRemaining,
            LocalDate projectedFinishDate,
            boolean countedToday,
            Instant startedAt,
            Instant completedAt,
            ActiveBatch activeBatch,
            int awaitingApplyCount,
            List<BatchSummary> batches
    ) {}

    public record ActiveBatch(
            String sessionId,
            int batchNumber,
            int totalCount,
            int countedCount
    ) {}

    public record BatchSummary(
            String sessionId,
            int batchNumber,
            String status,
            LocalDate sessionDate,
            int totalCount,
            int countedCount,
            int awaitingApplyCount,
            Instant closedAt
    ) {}

    public record BatchResponse(
            String sessionId,
            String cycleId,
            int batchNumber,
            String status,
            LocalDate sessionDate,
            int totalCount,
            int countedCount,
            boolean showsSystemStock,
            List<BatchLine> lines
    ) {}

    public record BatchLine(
            String lineId,
            String itemId,
            String itemName,
            String itemSku,
            String barcode,
            String categoryName,
            String unitType,
            String imageUrl,
            String aisle,
            BigDecimal countedQty,
            /** Null when the viewer counts blind. */
            BigDecimal systemQty,
            String status
    ) {}

    public record StartCountCycleRequest(
            @NotBlank String branchId,
            Integer batchSize
    ) {}

    public record CountBatchLineRequest(
            @NotNull @DecimalMin("0") BigDecimal countedQty
    ) {}

    public record ApplyBatchRequest(
            /** Lines to apply; null or empty applies every counted line in the batch. */
            List<String> lineIds
    ) {}
}
