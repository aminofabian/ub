package zelisline.ub.storefront.application;

import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;

/**
 * Validated storefront for a business slug (enabled flag, catalog branch active).
 */
public record PublicStorefrontContext(
        Business business,
        Branch catalogBranch,
        StorefrontSettingsResponse storefrontSettings
) {
}
