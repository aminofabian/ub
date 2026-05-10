package zelisline.ub.sales.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectDrawoutRequest(
        @NotBlank @Size(min = 1, max = 500) String rejectionReason
) {
}
