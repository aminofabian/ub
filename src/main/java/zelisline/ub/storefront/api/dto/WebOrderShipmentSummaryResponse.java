package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Carrier shipment summary on the merchant order detail (scope §8, §10). Null on
 * the DTO when the order has no shipment. Never carries the API key or the
 * Pickup Mtaani business id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebOrderShipmentSummaryResponse(
        String carrier,
        String mode,
        String destinationLabel,
        String locationDescription,
        BigDecimal quotedFeeKes,
        BigDecimal shopperFeeKes,
        String feeMode,
        String bookStatus,
        String bookError,
        String trackId,
        String receiptNo,
        String paymentStatus,
        String upstreamState,
        String lastTrackDescription,
        Instant lastPolledAt,
        Instant bookedAt
) {
}
