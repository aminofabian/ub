package zelisline.ub.payments.domain.spi;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Outbound disbursement via KopoKopo Send Money to an external recipient
 * ({@code mobile_wallet}, {@code till}, or {@code paybill}).
 */
public record SendMoneyRequest(
        Map<String, String> credentials,
        String callbackBaseUrl,
        /** KopoKopo destination type: mobile_wallet | till | paybill. */
        String destinationType,
        /** Required when destinationType is mobile_wallet. */
        String phoneNumber,
        /** Required when destinationType is till. */
        String tillNumber,
        /** Required when destinationType is paybill. */
        String paybillNumber,
        /** Required when destinationType is paybill. */
        String paybillAccountNumber,
        BigDecimal amount,
        String currency,
        String description,
        String sourceIdentifier,
        Map<String, String> metadata
) {
    public static final String DEST_MOBILE_WALLET = "mobile_wallet";
    public static final String DEST_TILL = "till";
    public static final String DEST_PAYBILL = "paybill";

    /** Convenience for M-Pesa phone destinations (Kiosk Pay withdraw, legacy callers). */
    public static SendMoneyRequest toMobileWallet(
            Map<String, String> credentials,
            String callbackBaseUrl,
            String phoneNumber,
            BigDecimal amount,
            String currency,
            String description,
            String sourceIdentifier,
            Map<String, String> metadata
    ) {
        return new SendMoneyRequest(
                credentials,
                callbackBaseUrl,
                DEST_MOBILE_WALLET,
                phoneNumber,
                null,
                null,
                null,
                amount,
                currency,
                description,
                sourceIdentifier,
                metadata);
    }
}
