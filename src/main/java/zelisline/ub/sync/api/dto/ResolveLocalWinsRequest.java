package zelisline.ub.sync.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveLocalWinsRequest(
        @NotBlank String resolvedSnapshot
) {
}
