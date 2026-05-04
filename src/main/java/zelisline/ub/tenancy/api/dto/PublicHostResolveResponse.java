package zelisline.ub.tenancy.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Public, unauthenticated lookup result for a browser Host/X-Forwarded-Host
 * reaching the Next.js storefront. Single source of tenant context: id, name,
 * lifecycle status, branding, auth config, and feature flags. Lets the
 * frontend map any mapped hostname (platform subdomain or custom domain) to
 * full tenant configuration without round-tripping the slug or the DB shape.
 *
 * <p>Forward-compatible: branding/authConfig/featureFlags currently come from
 * {@code businesses.settings} JSON. They can move to dedicated tables later
 * without breaking this contract.
 */
public record PublicHostResolveResponse(
        String tenantId,
        String tenantName,
        String slug,
        String status,
        TenantBrandingDto branding,
        TenantAuthConfigDto authConfig,
        Map<String, Boolean> featureFlags,
        boolean storefrontEnabled,
        Instant resolvedAt
) {
}
