package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PlatformKioskPaySettingsResponse(
        boolean enabled,
        BigDecimal feePercent,
        BigDecimal minWithdrawAmount,
        BigDecimal dailyWithdrawLimit,
        String currency,
        String paystackEnvironment,
        boolean hasPaystackCredentials,
        String paystackPublicKeyHint,
        String kopokopoEnvironment,
        boolean hasKopokopoCredentials,
        Instant sendMoneyFloatConstrainedUntil,
        Instant updatedAt
) {
}
