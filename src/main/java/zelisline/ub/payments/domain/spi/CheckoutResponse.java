package zelisline.ub.payments.domain.spi;

/**
 * Normalized response from initializing a provider-hosted checkout.
 */
public record CheckoutResponse(
        boolean accepted,
        String reference,
        String authorizationUrl,
        String accessCode,
        String providerTransactionId,
        /** Gateway-native status, when available (e.g. Paystack {@code pending}). */
        String status,
        String responseCode,
        String responseDescription,
        String rawPayload
) {

    public static CheckoutResponse accepted(
            String reference,
            String authorizationUrl,
            String accessCode,
            String providerTransactionId,
            String status,
            String rawPayload
    ) {
        return new CheckoutResponse(
                true, reference, authorizationUrl, accessCode, providerTransactionId,
                status, "0", "Accepted", rawPayload);
    }

    public static CheckoutResponse rejected(String responseCode, String responseDescription, String rawPayload) {
        return new CheckoutResponse(
                false, null, null, null, null, null, responseCode, responseDescription, rawPayload);
    }
}
