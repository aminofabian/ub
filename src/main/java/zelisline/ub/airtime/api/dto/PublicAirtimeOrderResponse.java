package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;

/** Shopper-facing order state — no merchant cost or margin. */
public record PublicAirtimeOrderResponse(
        String orderId,
        String phoneNumber,
        String network,
        BigDecimal amount,
        String currency,
        String status,
        /** true once the airtime has actually landed on the subscriber's line. */
        boolean delivered,
        boolean failed,
        boolean awaitingPayment,
        String checkoutRequestId,
        String receipt,
        String message
) {
}
