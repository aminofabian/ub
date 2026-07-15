package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

/**
 * One flagged item in the cost-audit list.
 *
 * @param costSource   where {@code effectiveCost} came from: {@code batch}, {@code reference}, or {@code none}
 * @param primaryIssue most severe flag: {@code zero_cost}, {@code sells_at_loss}, or {@code thin_margin}
 */
public record CostIssueRowResponse(
        String itemId,
        String name,
        String sku,
        String unitType,
        BigDecimal currentStock,
        BigDecimal activeQty,
        long activeBatchCount,
        BigDecimal effectiveCost,
        BigDecimal batchWac,
        BigDecimal buyingPrice,
        BigDecimal sellPrice,
        BigDecimal marginPct,
        String costSource,
        String primaryIssue,
        boolean zeroCost,
        boolean sellsAtLoss,
        boolean thinMargin
) {
}
