package zelisline.ub.sales.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VoidDrawoutRequest(
        @NotBlank @Size(min = 10, max = 500) String voidReason
) {
}
