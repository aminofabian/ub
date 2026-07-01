package zelisline.ub.tenancy.api.dto;

import java.util.Map;

/**
 * JSON shape compatible with {@code mobile/tenants/{slug}.json} for EAS white-label builds.
 */
public record MobileTenantProfileExport(
        String slug,
        String displayName,
        String tenantHostSuffix,
        String apiBaseURL,
        String splashBackgroundColor,
        String primaryColor,
        String scheme,
        Map<String, MobileTenantAppProfileExport> apps,
        MobileTenantAssetsExport assets
) {}
