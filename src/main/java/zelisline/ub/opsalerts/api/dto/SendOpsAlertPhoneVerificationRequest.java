package zelisline.ub.opsalerts.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendOpsAlertPhoneVerificationRequest(
        @NotBlank @Size(max = 32) String phone
) {
}
