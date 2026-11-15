package zelisline.ub.sales.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Who really did a paid service, and optionally why it is being changed. */
public record MoveServicePerformerRequest(
        @NotBlank @Size(max = 36) String staffProfileId,
        @Size(max = 300) String reason
) {
}
