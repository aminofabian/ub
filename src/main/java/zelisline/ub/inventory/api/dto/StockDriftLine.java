package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

/**
 * One item/branch whose branch on-hand (SUM of active batch remaining) does not yet reflect
 * historical batch-less sale outflows — the drift the oversell-deficit backfill repairs.
 */
public record StockDriftLine(
        String itemId,
        String itemName,
        String sku,
        String branchId,
        String branchName,
        /** Branch on-hand as the POS shows it today. */
        BigDecimal batchOnHand,
        /** Historical batch-less sale outflows ({@code <= 0}) not yet carried on a deficit batch. */
        BigDecimal unbackedOutflow,
        /** {@code batchOnHand} once the outstanding deficit is applied. */
        BigDecimal correctedOnHand,
        /** True when a {@code backfill} deficit batch already exists, so a re-run would skip it. */
        boolean alreadyRepaired
) {
}
