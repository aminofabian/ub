package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

/**
 * Super-admin update body for Kiosk Pay platform settings.
 * Null fields are left unchanged. Empty strings clear secrets when paired with clear* flags.
 */
public record UpdatePlatformKioskPaySettingsRequest(
        Boolean enabled,
        BigDecimal feePercent,
        BigDecimal minWithdrawAmount,
        BigDecimal dailyWithdrawLimit,
        String currency,
        String paystackEnvironment,
        String paystackPublicKey,
        String paystackSecretKey,
        Boolean clearPaystackCredentials,
        String kopokopoEnvironment,
        String kopokopoClientId,
        String kopokopoClientSecret,
        String kopokopoApiKey,
        String kopokopoTillNumber,
        Boolean clearKopokopoCredentials,
        Boolean clearSendMoneyFloatConstraint
) {
}
