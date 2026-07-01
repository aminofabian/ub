package zelisline.ub.storefront.api.dto;

/**
 * App Store / Play Store URLs. Platform links are shared Kiosk Ke listings;
 * per-role links are populated when a white-label build is published.
 */
public record PublicMobileStoreLinksResponse(
        String ios,
        String android
) {}
