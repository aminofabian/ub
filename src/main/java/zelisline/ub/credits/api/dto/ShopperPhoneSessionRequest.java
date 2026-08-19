package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShopperPhoneSessionRequest(
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(max = 128) String phoneVerificationToken,
        @NotBlank @Pattern(regexp = "\\d{4}", message = "Enter a 4-digit PIN") String pin,
        @Size(max = 4) String confirmPin,
        @Size(max = 120) String name
) {
}
