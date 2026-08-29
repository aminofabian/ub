package zelisline.ub.payroll.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StaffSmsPreviewRequest(
        @NotBlank @Size(max = 64) String templateKey,
        @Size(max = 500) String bodyOverride
) {
}
