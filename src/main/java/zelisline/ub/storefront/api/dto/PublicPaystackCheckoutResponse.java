package zelisline.ub.storefront.api.dto;

/**
 * Result of initializing a Paystack hosted checkout (scope doc §9 sketch).
 */
public record PublicPaystackCheckoutResponse(
        String checkoutId,
        String reference,
        String status,
        String authorizationUrl,
        /** Human-readable message for failures / UX. */
        String message
) {
}
