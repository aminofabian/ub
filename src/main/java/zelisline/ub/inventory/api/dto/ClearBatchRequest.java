package zelisline.ub.inventory.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClearBatchRequest(
        @NotBlank String reason,
        @Size(max = 500) String notes
) {
}
