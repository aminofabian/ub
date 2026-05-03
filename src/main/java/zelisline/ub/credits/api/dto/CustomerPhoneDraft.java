package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerPhoneDraft(
        @NotBlank @Size(max = 50) String phone,
        Boolean primary
) {
}
