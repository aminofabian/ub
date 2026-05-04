package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.Size;

public record RejectClaimRequest(
        @Size(max = 500) String reason
) {
}
