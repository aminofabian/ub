package zelisline.ub.tenancy.api.dto;

import zelisline.ub.storefront.api.dto.PublicMobileConfigResponse;

/**
 * Authenticated mobile distribution view for the current business, including an
 * EAS tenant profile export.
 */
public record MyMobileConfigResponse(
        PublicMobileConfigResponse config,
        MobileTenantProfileExport tenantProfile,
        boolean newlyProvisioned
) {}
