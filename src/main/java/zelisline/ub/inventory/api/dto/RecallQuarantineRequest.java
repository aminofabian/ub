package zelisline.ub.inventory.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecallQuarantineRequest(
        @NotBlank @Size(max = 64) String lotNumber,
        @Size(max = 36) String itemId,
        @NotBlank @Size(max = 255) String reason
) {
}
