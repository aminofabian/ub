package zelisline.ub.storefront.api.dto;

/**
 * One installable mobile surface (shopper, cashier, etc.) for a tenant.
 */
public record PublicMobileAppResponse(
        String role,
        String name,
        String bundleId,
        boolean whiteLabel,
        String embeddedTenantSlug,
        PublicMobileStoreLinksResponse storeLinks
) {}
