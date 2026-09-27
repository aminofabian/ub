package zelisline.ub.integrations.pickupmtaani.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Shopper-facing quote. {@code amountKes} is what will be added to the order
 * total (already adjusted by the tenant's fee mode). The upstream Pickup Mtaani
 * fee is deliberately not exposed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PickupMtaaniQuoteResponse(
        String quoteId,
        String mode,
        Long destinationId,
        String destinationLabel,
        BigDecimal amountKes,
        Instant expiresAt
) {
}
