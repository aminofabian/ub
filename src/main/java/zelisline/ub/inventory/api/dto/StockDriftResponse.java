package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only preview of the oversell-deficit drift: which item/branches still carry historical
 * batch-less sale outflows, and what the on-hand becomes once the backfill repairs them.
 */
public record StockDriftResponse(
        /** Item/branch pairs with historical batch-less sale outflows and cloud batches. */
        int candidates,
        /** Pairs the backfill would repair (not yet repaired). */
        int repairable,
        /** Pairs already carrying a backfill deficit batch. */
        int alreadyRepaired,
        /** Pairs skipped because the cloud ledger holds no batches for them (till-only). */
        int skippedNoBatches,
        /** Sum of the outstanding deficits the backfill would seed ({@code <= 0}). */
        BigDecimal totalOutstanding,
        /** True when more candidates exist than {@code limit} returned. */
        boolean truncated,
        List<StockDriftLine> lines
) {
}
