package zelisline.ub.platform.pageseal.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PageSealVerifySetRequest(
        @NotBlank String code,
        @NotBlank String pin,
        @NotBlank String confirmPin
) {
}
