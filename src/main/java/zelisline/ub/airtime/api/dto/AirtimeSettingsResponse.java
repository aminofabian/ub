package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;

/**
 * Tenant-facing airtime settings — the tenant's own toggles plus the platform
 * limits they operate inside, so the settings screen can explain itself without
 * a second round trip.
 */
public record AirtimeSettingsResponse(
        boolean enabled,
        boolean posEnabled,
        boolean storefrontEnabled,
        BigDecimal maxSingleAmount,
        boolean platformEnabled,
        boolean platformPosEnabled,
        boolean platformStorefrontEnabled,
        boolean platformCredentialsConfigured,
        BigDecimal commissionPercent,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal dailyLimit,
        String currency,
        /** Kiosk Pay must be active — the wallet is what funds airtime. */
        boolean walletActive,
        BigDecimal walletBalance,
        String blockedReason
) {
}
