package zelisline.ub.payments.domain.spi;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Outbound disbursement to a supplier M-Pesa wallet via KopoKopo.
 */
public record SendMoneyRequest(
        Map<String, String> credentials,
        String callbackBaseUrl,
        String phoneNumber,
        BigDecimal amount,
        String currency,
        String description,
        String sourceIdentifier,
        Map<String, String> metadata
) {
}
