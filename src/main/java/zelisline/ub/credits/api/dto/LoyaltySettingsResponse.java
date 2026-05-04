package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

public record LoyaltySettingsResponse(
        BigDecimal loyaltyPointsPerKes,
        BigDecimal loyaltyKesPerPoint,
        int loyaltyMaxRedeemBps
) {
}
