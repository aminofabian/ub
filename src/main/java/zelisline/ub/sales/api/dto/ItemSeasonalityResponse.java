package zelisline.ub.sales.api.dto;

import java.util.List;

public record ItemSeasonalityResponse(
        String itemId,
        String itemName,
        String itemSku,
        String view,
        List<ItemMonthBucket> months,
        List<Integer> peakMonths,
        String peakLabel,
        long linkedSaleCount,
        List<CompanionSkuRow> companions
) {
}
