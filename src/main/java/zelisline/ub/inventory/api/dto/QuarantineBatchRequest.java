package zelisline.ub.inventory.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuarantineBatchRequest(@NotBlank @Size(max = 255) String reason) {
}
