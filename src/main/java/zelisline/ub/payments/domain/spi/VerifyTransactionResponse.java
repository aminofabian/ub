package zelisline.ub.payments.domain.spi;

import java.math.BigDecimal;

/**
 * Normalized result of a provider transaction verification.
 */
public record VerifyTransactionResponse(
        boolean completed,
        boolean failed,
        boolean pending,
        /** Gateway-native status string (e.g. Paystack {@code success}). */
        String providerStatus,
        String providerTransactionId,
        String reference,
        /** Decimal amount as reported by the provider (converted from minor units). */
        BigDecimal amount,
        String currency,
        String failureMessage,
        String rawPayload,
        /**
         * Provider processing fee in major currency units (e.g. Paystack {@code fees}),
         * when reported. Null when unknown.
         */
        BigDecimal providerFee
) {

    public VerifyTransactionResponse(
            boolean completed,
            boolean failed,
            boolean pending,
            String providerStatus,
            String providerTransactionId,
            String reference,
            BigDecimal amount,
            String currency,
            String failureMessage,
            String rawPayload
    ) {
        this(
                completed,
                failed,
                pending,
                providerStatus,
                providerTransactionId,
                reference,
                amount,
                currency,
                failureMessage,
                rawPayload,
                null);
    }

    public static VerifyTransactionResponse pending(String providerStatus, String rawPayload) {
        return new VerifyTransactionResponse(
                false, false, true, providerStatus, null, null, null, null, null, rawPayload, null);
    }

    public static VerifyTransactionResponse failed(
            String providerStatus, String failureMessage, String rawPayload) {
        return new VerifyTransactionResponse(
                false, true, false, providerStatus, null, null, null, null, failureMessage, rawPayload, null);
    }
}
