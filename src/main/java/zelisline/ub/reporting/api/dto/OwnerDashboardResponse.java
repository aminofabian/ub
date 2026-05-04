package zelisline.ub.reporting.api.dto;

import java.math.BigDecimal;
import java.util.List;

import zelisline.ub.finance.api.dto.FinancePulseResponse;
import zelisline.ub.purchasing.api.dto.ApAgingTotalsResponse;

/** Phase 7 Slice 5 — composite owner dashboard (`implement.md` §9.1 baseline). */
public record OwnerDashboardResponse(
        FinancePulseResponse pulseToday,
        ApAgingTotalsResponse payablesAging,
        List<TopSkuByRevenue> topSkusLast30Days
) {

    public record TopSkuByRevenue(String itemId, String itemName, BigDecimal revenueLast30Days) {
    }
}
