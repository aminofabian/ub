package zelisline.ub.tenancy.api.dto;

import java.time.Instant;

public record BusinessResponse(
        String id,
        String name,
        String slug,
        String currency,
        String countryCode,
        String timezone,
        boolean active,
        String subscriptionTier,
        Instant createdAt,
        Instant updatedAt,
        StorefrontSettingsResponse storefront,
        InventorySettingsResponse inventory,
        ProfileSettingsResponse profile,
        OnboardingSettingsResponse onboarding,
        TenantBrandingDto branding,
        java.util.Map<String, Boolean> featureFlags,
        CashierDrawoutAccessResponse cashierDrawout,
        HubAlertsSettingsResponse hubAlerts,
        MetaCapiSettingsResponse metaCapi,
        // Hostname of the active primary domain mapping, if any. Used by the
        // app shell to keep cross-origin redirects (login handoff, share
        // links) anchored to the tenant's chosen primary host instead of a
        // slug-derived fallback.
        String primaryDomain,
        /** Optional override of regional catalog resolution ({@code settings.globalCatalogCode}). */
        String globalCatalogCode,
        /** Highest receipt number already issued for this business, if any. */
        Long lastReceiptNo,
        /**
         * Configured floor for the next POS receipt number ({@code settings.nextReceiptNo}).
         * Allocation uses {@code max(last+1, nextReceiptNo)}.
         */
        Long nextReceiptNo
) {
}
