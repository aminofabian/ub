package zelisline.ub.messaging.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Super Admin SMS credit configuration + tier allowance DTOs (§10, §11). */
public record PlatformSmsCreditSettingsDtos() {

    public record SettingsResponse(
            boolean enabled,
            BigDecimal unitPriceKes,
            int minPurchaseCredits,
            int maxPurchaseCredits,
            int lowBalanceThreshold,
            String cycleTimezone,
            Instant updatedAt
    ) {
    }

    public record UpdateSettingsRequest(
            Boolean enabled,
            BigDecimal unitPriceKes,
            Integer minPurchaseCredits,
            Integer maxPurchaseCredits,
            Integer lowBalanceThreshold,
            String cycleTimezone
    ) {
    }

    public record TierAllowanceResponse(
            String tierCode,
            int includedSmsPerMonth,
            boolean active
    ) {
    }

    public record UpdateTierAllowanceRequest(
            Integer includedSmsPerMonth,
            Boolean active
    ) {
    }

    public record TiersResponse(
            List<TierAllowanceResponse> tiers
    ) {
    }
}
