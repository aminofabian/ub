package zelisline.ub.payments.domain.spi;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Normalized request for initiating an STK Push across any gateway.
 *
 * <p>The {@code credentials} map contains the decrypted gateway-specific
 * key-value pairs (e.g. {@code clientId}, {@code consumerKey}, {@code passkey}).
 * The gateway implementation extracts what it needs.
 */
public record StkPushRequest(
        String businessId,
        String phoneNumber,
        BigDecimal amount,
        String reference,
        String description,
        String callbackBaseUrl,
        Map<String, String> credentials
) {
}
