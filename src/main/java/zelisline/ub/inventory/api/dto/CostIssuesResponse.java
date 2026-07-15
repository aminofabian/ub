package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** Cost-audit list payload: the flagged items plus per-flag counts. */
public record CostIssuesResponse(
        String branchId,
        BigDecimal thinMarginPct,
        int total,
        int zeroCostCount,
        int sellsAtLossCount,
        int thinMarginCount,
        List<CostIssueRowResponse> items
) {
}
