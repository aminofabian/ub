package zelisline.ub.tenancy.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row of the apex shop directory search (Phase 4). Shop names, slugs, and
 * branding are public; the host is the tenant's own primary host, so the
 * frontend can build forward URLs exclusively from the resolved tenant record.
 *
 * @param slug       canonical URL slug (the forward host derives from this or {@code primaryHost})
 * @param name       display name (public directory data)
 * @param logoUrl    branding logo when the tenant set one (public)
 * @param primaryHost the tenant's own active primary host, or {@code null} when
 *                    none is mapped and no platform subdomain suffix is configured
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicShopsSearchResponse(
        String slug,
        String name,
        String logoUrl,
        String primaryHost
) {
}
