package zelisline.ub.payments.domain.spi;

/**
 * Result of initiating KopoKopo Send Money (B2C / disbursement).
 */
public record SendMoneyResult(
        boolean accepted,
        String sendMoneyId,
        String message,
        String responseCode
) {
    public static SendMoneyResult rejected(String code, String message) {
        return new SendMoneyResult(false, null, message, code);
    }

    public static SendMoneyResult accepted(String sendMoneyId) {
        return new SendMoneyResult(true, sendMoneyId, "Accepted", "0");
    }
}
