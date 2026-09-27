package zelisline.ub.integrations.pickupmtaani.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Public quote request from storefront checkout (scope §7, §15).
 */
public record PickupMtaaniQuoteRequest(
        /** {@code agent} or {@code doorstep}. */
        @NotBlank @Size(max = 16)
        String mode,
        /** Destination agent id, or doorstep destination id, per mode. */
        @NotNull
        Long destinationId,
        /** Display label the shopper already has; stored for the order. */
        @Size(max = 255)
        String destinationLabel,
        /** Doorstep landmark; recorded but not required for a quote. */
        @Size(max = 500)
        String locationDescription
) {
}
