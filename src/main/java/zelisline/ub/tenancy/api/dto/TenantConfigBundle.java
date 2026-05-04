package zelisline.ub.tenancy.api.dto;

import java.util.Map;

/**
 * Internal bundle returned by {@code StorefrontSettingsService.readTenantConfig}
 * to keep the host-resolve assembly to a single JSON parse.
 */
public record TenantConfigBundle(
        TenantBrandingDto branding,
        TenantAuthConfigDto authConfig,
        Map<String, Boolean> featureFlags
) {

    public static TenantConfigBundle defaults(String displayName) {
        return new TenantConfigBundle(
                TenantBrandingDto.defaults(displayName),
                TenantAuthConfigDto.defaults(),
                Map.of()
        );
    }
}
