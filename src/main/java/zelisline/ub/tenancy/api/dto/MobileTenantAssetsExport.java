package zelisline.ub.tenancy.api.dto;

/**
 * Asset paths placeholder for generated tenant profiles.
 */
public record MobileTenantAssetsExport(
        String icon,
        String splashImage,
        String adaptiveIcon,
        String favicon
) {
    public static MobileTenantAssetsExport placeholders(String slug) {
        String base = "../../tenants/" + slug + "/assets";
        return new MobileTenantAssetsExport(
                base + "/icon.png",
                base + "/splash.png",
                base + "/adaptive-icon.png",
                base + "/favicon.png"
        );
    }
}
