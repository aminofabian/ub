package zelisline.ub.marketplace.api.dto;

import java.util.List;

public record GlobalSupplierHubResponse(
        String username,
        String displayName,
        int shopCount,
        String currency,
        GlobalSupplierHubTotals totals,
        List<GlobalSupplierHubShopCard> shops,
        boolean pageSealed,
        boolean pageUnlocked
) {
}
