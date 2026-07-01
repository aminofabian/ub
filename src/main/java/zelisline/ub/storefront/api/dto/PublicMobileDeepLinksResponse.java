package zelisline.ub.storefront.api.dto;

/**
 * Native app deep links and universal-link fallbacks for a tenant.
 */
public record PublicMobileDeepLinksResponse(
        String shopper,
        String cashier,
        String grocery,
        String admin,
        String stock,
        String tenant,
        String universalShop,
        String universalApp
) {}
