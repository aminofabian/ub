package zelisline.ub.tenancy.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

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
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicHostResolveResponse(
        String tenantId,
        String tenantName,
        String slug,
        String status,
        TenantBrandingDto branding,
        TenantAuthConfigDto authConfig,
        Map<String, Boolean> featureFlags,
        boolean storefrontEnabled,
        Instant resolvedAt,
        /** ISO-3166 alpha-2 when known (e.g. {@code KE}). */
        String countryCode,
        /**
         * Short area/locality labels derived from onboarding + active branches
         * (e.g. {@code Westlands}) for SEO snippets.
         */
        List<String> branchLocalities
) {
}
