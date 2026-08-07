package zelisline.ub.payments.domain.spi;

import java.util.Map;

/**
 * Normalized request for a server-side provider transaction verification
 * (e.g. Paystack {@code GET /transaction/verify/{reference}}).
 */
public record VerifyTransactionRequest(
        String reference,
        Map<String, String> credentials
) {
}
