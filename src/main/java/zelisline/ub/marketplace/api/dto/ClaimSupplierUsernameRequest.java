package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClaimSupplierUsernameRequest(
        @NotBlank @Size(min = 2, max = 64) String username
) {
}
