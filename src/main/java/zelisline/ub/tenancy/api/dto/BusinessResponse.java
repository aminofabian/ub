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
        TenantBrandingDto branding,
        // Hostname of the active primary domain mapping, if any. Used by the
        // app shell to keep cross-origin redirects (login handoff, share
        // links) anchored to the tenant's chosen primary host instead of a
        // slug-derived fallback.
        String primaryDomain
) {
}
