package zelisline.ub.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Review a single product with the AI guide and return improvement suggestions. */
public record ProductPolishRequest(
        @NotBlank @Size(max = 36) String itemId
) {
}
