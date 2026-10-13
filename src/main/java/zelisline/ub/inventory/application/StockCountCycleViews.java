package zelisline.ub.inventory.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.ActiveBatch;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.BatchLine;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.BatchResponse;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.BatchSummary;
import zelisline.ub.inventory.api.dto.StockCountCycleDtos.CountCycleResponse;
import zelisline.ub.inventory.domain.StockCountCycle;
import zelisline.ub.inventory.domain.StockTakeLine;
import zelisline.ub.inventory.domain.StockTakeSession;

/** Maps full-count cycles and their batch sessions to API responses. */
@Component
@RequiredArgsConstructor
public class StockCountCycleViews {

    private final CountItemCatalog countItemCatalog;

    public CountCycleResponse cycle(
            StockCountCycle cycle,
            List<StockTakeSession> sessions,
            LocalDate today,
            ZoneId zone
    ) {
        List<BatchSummary> batches = batchSummaries(sessions);
        boolean countedToday = sessions.stream().anyMatch(s -> closedOn(s, today, zone));
        int remaining = cycle.getTotalItems() - cycle.getCountedItems();
        return new CountCycleResponse(
                cycle.getId(),
                cycle.getBranchId(),
                cycle.getStatus(),
                cycle.getBatchSize(),
                cycle.getTotalItems(),
                cycle.getCountedItems(),
                remaining,
                (int) batches.stream().filter(b -> isClosed(b.status())).count(),
                CountCyclePlanner.batchesRemaining(remaining, cycle.getBatchSize()),
                CountCyclePlanner.projectedFinish(today, remaining, cycle.getBatchSize(), countedToday),
                countedToday,
                cycle.getStartedAt(),
                cycle.getCompletedAt(),
                activeBatch(cycle, batches),
                batches.stream().mapToInt(BatchSummary::awaitingApplyCount).sum(),
                batches
        );
    }

    public BatchResponse batch(
            StockTakeSession session,
            int batchNumber,
            boolean showSystemStock
    ) {
        List<String> itemIds = session.getLines().stream().map(StockTakeLine::getItemId).toList();
        CountItemCatalog.Context ctx = countItemCatalog.load(session.getBusinessId(), itemIds);
        List<BatchLine> lines = session.getLines().stream()
                .map(line -> batchLine(line, ctx, showSystemStock))
                .toList();
        return new BatchResponse(
                session.getId(),
                session.getCountCycleId(),
                batchNumber,
                session.getStatus(),
                session.getSessionDate(),
                lines.size(),
                (int) session.getLines().stream().filter(StockCountCycleViews::isCounted).count(),
                showSystemStock,
                lines
        );
    }

    public static boolean isCounted(StockTakeLine line) {
        return line.getCountedQty() != null;
    }

    private static BatchLine batchLine(
            StockTakeLine line,
            CountItemCatalog.Context ctx,
            boolean showSystemStock
    ) {
        Item item = ctx.itemsById().get(line.getItemId());
        return new BatchLine(
                line.getId(),
                line.getItemId(),
                CountItemCatalog.displayName(item, line.getItemId()),
                item != null ? item.getSku() : null,
                item != null ? item.getBarcode() : null,
                ctx.categoryName(item),
                item != null ? item.getUnitType() : null,
                ctx.thumbs().get(line.getItemId()),
                line.getAisle(),
                line.getCountedQty(),
                showSystemStock ? line.getSystemQtySnapshot() : null,
                line.getStatus()
        );
    }

    private static List<BatchSummary> batchSummaries(List<StockTakeSession> sessions) {
        List<BatchSummary> out = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            StockTakeSession s = sessions.get(i);
            int awaiting = (int) s.getLines().stream()
                    .filter(l -> InventoryConstants.STOCKTAKE_LINE_SUBMITTED.equals(l.getStatus()))
                    .count();
            out.add(new BatchSummary(
                    s.getId(),
                    i + 1,
                    s.getStatus(),
                    s.getSessionDate(),
                    s.getLines().size(),
                    (int) s.getLines().stream().filter(StockCountCycleViews::isCounted).count(),
                    isClosed(s.getStatus()) ? awaiting : 0,
                    s.getClosedAt()
            ));
        }
        return out;
    }

    private static ActiveBatch activeBatch(StockCountCycle cycle, List<BatchSummary> batches) {
        return batches.stream()
                .filter(b -> b.sessionId().equals(cycle.getActiveSessionId()))
                .findFirst()
                .map(b -> new ActiveBatch(b.sessionId(), b.batchNumber(), b.totalCount(), b.countedCount()))
                .orElse(null);
    }

    private static boolean closedOn(StockTakeSession session, LocalDate day, ZoneId zone) {
        return session.getClosedAt() != null
                && session.getClosedAt().atZone(zone).toLocalDate().equals(day);
    }

    private static boolean isClosed(String status) {
        return InventoryConstants.STOCKTAKE_SESSION_CLOSED.equals(status);
    }
}
