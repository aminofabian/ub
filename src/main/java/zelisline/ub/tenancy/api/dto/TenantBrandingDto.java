package zelisline.ub.tenancy.api.dto;

/**
 * Tenant-facing branding bundle returned by the public host-resolve endpoint.
 * Stored under the {@code branding} namespace inside {@code businesses.settings}
 * JSON; missing fields fall back to safe defaults so the storefront always has
 * something to render.
 */
public record TenantBrandingDto(
        String displayName,
        String logoUrl,
        String faviconUrl,
        String primaryColor,
        String accentColor,

        // SEO / social preview fields (optional)
        String metaTitle,
        String metaDescription,
        String ogImage,
        String metaKeywords
) {

    public static TenantBrandingDto defaults(String displayName) {
        return new TenantBrandingDto(displayName, null, null, null, null, null, null, null, null);
    }
}
