package zelisline.ub.payments.domain.spi;

/**
 * Normalized response from a gateway STK Push initiation.
 */
public record StkPushResponse(
        /** Gateway-native checkout / transaction ID for status polling. */
        String gatewayCheckoutRequestId,

        /** Gateway-native merchant request ID, if available. */
        String gatewayMerchantRequestId,

        /** Raw response code from the gateway. */
        String responseCode,

        /** Human-readable description from the gateway. */
        String responseDescription,

        /** Whether the push was accepted by the gateway. */
        boolean accepted
) {

    public static StkPushResponse accepted(String checkoutRequestId, String merchantRequestId,
                                           String responseCode, String responseDescription) {
        return new StkPushResponse(checkoutRequestId, merchantRequestId, responseCode, responseDescription, true);
    }

    public static StkPushResponse rejected(String responseCode, String responseDescription) {
        return new StkPushResponse(null, null, responseCode, responseDescription, false);
    }
}
