package zelisline.ub.inventory.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The bar usage report (ADR 0024): for each bottle at a branch over a window, where its stock went —
 * pours, direct sales and waste read from the stock-movement ledger — and the variance against a
 * physical count. Unlike the morning/evening reconciliation, this reads movement provenance, so a
 * bottle depleted only by pours (never a sale line) is accounted for.
 *
 * @param branchId the branch
 * @param from     window start (inclusive)
 * @param to       window end (exclusive)
 * @param lines    one per bottle, those with any movement or stock first
 */
public record BarUsageResponse(
        String branchId,
        Instant from,
        Instant to,
        int bottleCount,
        int withVariance,
        List<BarUsageLine> lines
) {
}
