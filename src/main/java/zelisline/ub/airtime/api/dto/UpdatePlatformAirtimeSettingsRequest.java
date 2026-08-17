package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;

/**
 * Super-admin update body for platform airtime settings.
 * Null fields are left unchanged; secrets are only replaced when supplied.
 */
public record UpdatePlatformAirtimeSettingsRequest(
        Boolean enabled,
        String baseUrl,
        String environment,
        String consumerKey,
        String consumerSecret,
        Boolean clearCredentials,
        BigDecimal tenantCommissionPercent,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal dailyTenantLimit,
        String currency,
        Boolean posEnabled,
        Boolean storefrontEnabled,
        BigDecimal floatLowThreshold,
        Boolean clearFloatConstraint
) {
}
