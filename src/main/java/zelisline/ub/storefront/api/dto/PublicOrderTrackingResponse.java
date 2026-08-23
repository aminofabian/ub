package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Public guest order tracking snapshot (scope §15, Phase 2). Never includes the
 * full address or email — access is gated by code + phone last-4, or by the
 * Phase 5 one-tap receipt token (`receiptVerified`).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicOrderTrackingResponse(
        String orderId,
        String orderCode,
        String status,
        String fulfillmentStatus,
        BigDecimal grandTotal,
        String currency,
        String catalogBranchName,
        Instant createdAt,
        /**
         * Phase 5: set only on the token path — the order's contact phone, so the
         * verified holder can continue into the sign-in sheet prefilled. Omitted
         * on the phone-last-4 path.
         */
        String customerPhone,
        /** Phase 5: true only when the single-use receipt token was verified. */
        Boolean receiptVerified
) {
}
