package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.util.List;

import zelisline.ub.credits.api.dto.TabPurchaseRowResponse;

public record ShopperAccountOverviewResponse(
        String email,
        boolean linkedStorefrontProfile,
        String customerDirectoryName,
        ShopperBalancesResponse balances,
        List<WebOrderSummaryResponse> pickupOrders,
        long pickupOrdersTotal,
        int pickupOrdersPage,
        int pickupOrdersPageSize,
        int pickupOrdersTotalPages,
        List<ShopperLedgerLineResponse> recentLedgerLines,
        int ledgerLinesTotal,
        boolean ledgerTruncated,
        BigDecimal loyaltyKesPerPoint,
        String tabPhone,
        List<TabPurchaseRowResponse> tillPurchases
) {
}
