package zelisline.ub.grocery.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelGroceryInvoiceRequest(
        @NotBlank String reason
) {
}
