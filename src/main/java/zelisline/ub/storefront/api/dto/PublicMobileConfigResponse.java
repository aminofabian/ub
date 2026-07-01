package zelisline.ub.storefront.api.dto;

import java.util.List;

import zelisline.ub.tenancy.api.dto.TenantBrandingDto;

/**
 * Public mobile distribution config for a business slug.
 *
 * <p>Used by native apps, QR codes on the storefront, and admin "Get the app" UI.
 */
public record PublicMobileConfigResponse(
        String tenantId,
        String slug,
        String displayName,
        String tenantHost,
        String tenantStatus,
        boolean storefrontEnabled,
        String apiBaseUrl,
        TenantBrandingDto branding,
        PublicMobileDeepLinksResponse deepLinks,
        PublicMobileStoreLinksResponse platformStoreLinks,
        List<PublicMobileAppResponse> apps
) {}
