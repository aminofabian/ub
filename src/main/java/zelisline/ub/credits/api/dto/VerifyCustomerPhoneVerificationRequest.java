package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyCustomerPhoneVerificationRequest(
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(min = 4, max = 12) String code
) {
}
