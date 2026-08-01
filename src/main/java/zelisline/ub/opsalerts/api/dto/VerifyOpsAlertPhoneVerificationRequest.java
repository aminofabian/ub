package zelisline.ub.opsalerts.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyOpsAlertPhoneVerificationRequest(
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(min = 4, max = 8) String code
) {
}
