package zelisline.ub.storefront.api.dto;

/**
 * Body for initializing a Paystack hosted checkout for a web order.
 */
public record PublicPaystackCheckoutRequest(
        /** Optional: the tenant's Paystack config id; defaults to the ACTIVE one. */
        String configId,
        /** Customer email for the hosted page; falls back to the order email. */
        String email,
        /**
         * Browser origin ({@code window.location.origin}) so the Paystack
         * callback returns the shopper to the same host (custom domain / platform
         * subdomain). Untrusted origins are ignored server-side.
         */
        String returnOrigin
) {
}
