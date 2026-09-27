package zelisline.ub.storefront.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shopper's Pickup Mtaani fulfilment choice on the checkout delivery step
 * (scope §7, §15). Present only when the shopper picked Pickup Mtaani; absent
 * means the normal delivery-area flow.
 */
public record PickupMtaaniFulfillmentRequest(
        /** Only {@code pickup_mtaani} is accepted in V1. */
        @NotBlank @Size(max = 32)
        String carrier,
        /** {@code agent} or {@code doorstep}. */
        @NotBlank @Size(max = 16)
        String mode,
        /** Destination agent id, or doorstep destination id, per mode. */
        @NotNull @Min(1)
        Long destinationId,
        @Size(max = 255)
        String destinationLabel,
        /** Doorstep landmark; required by the client for doorstep mode. */
        @Size(max = 500)
        String locationDescription,
        /** Quote id from {@code POST .../pickup-mtaani/quote}. */
        @NotBlank @Size(max = 64)
        String quoteId
) {
}
