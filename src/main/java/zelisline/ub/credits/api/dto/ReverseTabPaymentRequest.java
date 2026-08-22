package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReverseTabPaymentRequest(
        @NotBlank @Size(max = 36) String customerId
) {
}
