package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Admin payload for tuning the per-business loyalty earn/redeem economy. All three
 * fields are required so admins make a deliberate, atomic choice of the program shape.
 */
public record UpdateLoyaltySettingsRequest(
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal loyaltyPointsPerKes,
        @NotNull @DecimalMin(value = "0.00000001", inclusive = true) BigDecimal loyaltyKesPerPoint,
        @NotNull @Min(0) @Max(10000) Integer loyaltyMaxRedeemBps
) {
}
