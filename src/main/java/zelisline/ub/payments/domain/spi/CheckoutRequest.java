package zelisline.ub.payments.domain.spi;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Normalized request for initializing a provider-hosted checkout
 * (e.g. Paystack {@code /transaction/initialize}).
 *
 * <p>The {@code credentials} map contains the decrypted gateway-specific
 * key-value pairs. Domain amounts are decimal (whole currency units, e.g.
 * KES) — conversion to provider minor units happens inside the gateway
 * implementation, at this boundary.
 */
public record CheckoutRequest(
        String businessId,
        String configId,
        BigDecimal amount,
        String currency,
        String email,
        /** Globally unique; doubles as the webhook routing key. */
        String reference,
        String description,
        /** Browser-facing URL Paystack redirects the shopper to after payment. */
        String callbackUrl,
        /** Routing / context fields echoed back by the provider on webhooks. */
        Map<String, String> metadata,
        Map<String, String> credentials
) {
}
