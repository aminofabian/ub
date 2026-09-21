package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

/**
 * Result of an onboarding receive-test STK: config saved + prompt sent (or declined).
 */
public record CustodyReceiveTestResponse(
        boolean accepted,
        String configId,
        String checkoutRequestId,
        String message,
        BigDecimal amount,
        String phoneNumber,
        String destinationSummary
) {
}
