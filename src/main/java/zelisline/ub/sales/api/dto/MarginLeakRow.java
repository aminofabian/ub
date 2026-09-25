package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record MarginLeakRow(
        String itemId,
        String itemName,
        String sku,
        BigDecimal quantitySold,
        BigDecimal netRevenue,
        BigDecimal netProfit,
        BigDecimal shareOfLossPct,
        List<String> reasons,
        /** Base units removed per pack sold. Null when this SKU is not a pack. */
        BigDecimal unitsPerPack,
        /** Product whose stock the pack draws from. Null when this SKU is not a pack. */
        String stockSourceName
) {
}
