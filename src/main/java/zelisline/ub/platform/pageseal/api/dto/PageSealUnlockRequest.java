package zelisline.ub.platform.pageseal.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PageSealUnlockRequest(@NotBlank String pin) {
}
