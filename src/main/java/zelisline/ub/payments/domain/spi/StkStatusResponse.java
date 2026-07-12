package zelisline.ub.payments.domain.spi;

/**
 * Normalized STK Push status from a gateway query.
 */
public record StkStatusResponse(
        /** Gateway-native result code (e.g. Daraja {@code 0} = success). */
        String resultCode,

        /** Human-readable description. */
        String resultDescription,

        /** Whether the customer completed the payment. */
        boolean completed,

        /** Whether the customer cancelled or the request timed out. */
        boolean failed,

        /**
         * M-Pesa receipt / till reference when {@link #completed} is true
         * (KopoKopo {@code event.resource.reference}, e.g. {@code OJL7OW3J59}).
         */
        String mpesaReceipt,

        /** Raw JSON from the gateway, for debugging. */
        String rawPayload
) {

    public boolean isPending() {
        return !completed && !failed;
    }
}
