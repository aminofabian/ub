package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PlatformAirtimeSettingsResponse(
        boolean enabled,
        String provider,
        String baseUrl,
        String environment,
        boolean hasCredentials,
        String consumerKeyHint,
        BigDecimal tenantCommissionPercent,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal dailyTenantLimit,
        String currency,
        boolean posEnabled,
        boolean storefrontEnabled,
        BigDecimal floatBalance,
        BigDecimal floatLowThreshold,
        boolean floatLow,
        Instant floatCheckedAt,
        Instant floatConstrainedUntil,
        Instant updatedAt
) {
}
