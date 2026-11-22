package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The phone number and the token its code verification returned. */
public record OpenBuyerSessionRequest(
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(max = 255) String verificationToken
) {
}
