package zelisline.ub.storefront.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateWebOrderFulfillmentRequest(
        @NotBlank String fulfillmentStatus
) {
}
