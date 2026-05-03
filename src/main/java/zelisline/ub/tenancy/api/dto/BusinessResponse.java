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
        StorefrontSettingsResponse storefront
) {
}
