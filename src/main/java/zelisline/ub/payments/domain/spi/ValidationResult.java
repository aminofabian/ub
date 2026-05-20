package zelisline.ub.payments.domain.spi;

/**
 * Result of a gateway connectivity / credential validation test.
 */
public record ValidationResult(
        /** Whether the test passed. */
        boolean valid,

        /** Machine-readable error code (e.g. {@code AUTH_FAILED}, {@code TIMEOUT}). */
        String errorCode,

        /** Human-readable error message. */
        String errorMessage,

        /** Gateway-native response for debugging. */
        String rawResponse
) {

    public static ValidationResult success() {
        return new ValidationResult(true, null, null, null);
    }

    public static ValidationResult failure(String errorCode, String errorMessage, String rawResponse) {
        return new ValidationResult(false, errorCode, errorMessage, rawResponse);
    }
}
