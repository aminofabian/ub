package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;

public record ShopperBalancesResponse(
        BigDecimal walletBalance,
        BigDecimal balanceOwed,
        BigDecimal creditLimit,
        /** creditLimit − balance owed when {@code creditLimit} is non-null; otherwise null. */
        BigDecimal creditAvailable,
        int loyaltyPoints
) {
}
