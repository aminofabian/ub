package zelisline.ub.tenancy.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateBusinessRequest(
        @Size(max = 255) String name,
        @Size(max = 64) String subscriptionTier,
        Boolean active,
        @Valid StorefrontPatchRequest storefront,
        @Valid InventoryPatchRequest inventory,
        @Valid ProfilePatchRequest profile,
        @Valid FeatureFlagsPatchRequest featureFlags,
        /**
         * Published global catalog code override. Null = unchanged; blank = clear
         * override and fall back to country/default resolution.
         */
        @Size(max = 64) String globalCatalogCode,
        /** ISO 4217; null = unchanged. Locked after onboarding completed/dismissed for tenants. */
        @Size(min = 3, max = 3)
        @Pattern(regexp = "[A-Za-z]{3}")
        String currency,
        /** ISO 3166-1 alpha-2; null = unchanged. Locked after onboarding completed/dismissed for tenants. */
        @Size(min = 2, max = 2)
        @Pattern(regexp = "[A-Za-z]{2}")
        String countryCode,
        /** IANA timezone; null = unchanged. Locked after onboarding completed/dismissed for tenants. */
        @Size(max = 100) String timezone,
        /**
         * Super Admin only: required when changing country/currency on a business that already
         * has products or sales. Confirms the operator understands amounts are re-labeled, not converted.
         */
        Boolean acknowledgeRegionRisk
) {
}
