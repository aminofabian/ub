package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShopperPhoneSendRequest(
        @NotBlank @Size(max = 32) String phone
) {
}
