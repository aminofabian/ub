package zelisline.ub.tenancy.api.dto;

/**
 * App Store / Play Store URLs for a tenant's mobile apps.
 */
public record MobileStoreLinksDto(
        String ios,
        String android
) {
    public static MobileStoreLinksDto empty() {
        return new MobileStoreLinksDto(null, null);
    }
}
