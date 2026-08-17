package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** What a shopper is allowed to buy. Deliberately says nothing about the merchant's wallet. */
public record PublicAirtimeConfigResponse(
        boolean available,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String currency,
        List<BigDecimal> quickAmounts,
        String reason,
        /** Present on the customer tab; empty on the anonymous storefront. */
        PublicAirtimeRecentsResponse recents
) {
    public PublicAirtimeConfigResponse {
        recents = recents != null ? recents : PublicAirtimeRecentsResponse.empty();
    }
}
