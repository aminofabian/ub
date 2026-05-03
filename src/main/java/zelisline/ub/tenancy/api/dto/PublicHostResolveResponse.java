package zelisline.ub.tenancy.api.dto;

/**
 * Public, unauthenticated lookup result for a browser Host/X-Forwarded-Host
 * reaching the Next.js storefront. Lets the frontend map any mapped hostname
 * (platform subdomain or custom domain) to the tenant slug without embedding
 * it in the URL.
 */
public record PublicHostResolveResponse(
        String slug,
        String businessId,
        String businessName,
        boolean storefrontEnabled
) {
}
